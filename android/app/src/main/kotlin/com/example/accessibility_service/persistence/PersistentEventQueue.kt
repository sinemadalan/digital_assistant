package com.example.accessibility_service.persistence

import android.content.Context
import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.util.zip.CRC32
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

sealed interface EnqueueResult {
    data class Enqueued(val sequence: Long, val droppedOldestCount: Int) : EnqueueResult
    data class Oversized(val recordBytes: Int, val usableCapacityBytes: Int) : EnqueueResult
}

class QueueBatchToken internal constructor(
    internal val readPosition: Int,
    internal val sequences: List<Long>,
    internal val droppedDueToCapacity: Long,
    internal val ownerMarker: Any,
    internal val nonCapacityHeadChangeEpoch: Long,
)

sealed interface AcknowledgeResult {
    data object FullyAcknowledged : AcknowledgeResult
    data class RemainingSuffixAcknowledged(val evictedPrefixCount: Int) : AcknowledgeResult
    data object AlreadyEvicted : AcknowledgeResult
    data object Stale : AcknowledgeResult
}

data class QueuedCaptureBatch(
    val captures: List<QueuedCapture>,
    val acknowledgmentToken: QueueBatchToken?,
)

data class QueueDiagnostics(
    val pendingRecordCount: Int,
    val droppedDueToCapacity: Long,
    val corruptRecordCount: Long,
    val oversizedRecordCount: Long,
)

class PersistentEventQueue private constructor(
    private val queueFile: File,
    private val ownershipKey: String,
    private val dataCapacityBytes: Int,
    private val diagnosticLogger: (String) -> Unit,
) {
    private val mutex = Mutex()
    private val randomAccessFile: RandomAccessFile
    private val channel: FileChannel
    private val mappedBuffer: MappedByteBuffer
    private var metadata: Metadata
    private var stableHeaderSlot = 0
    private var workingHeaderSlot = 1
    private var workingHeaderDirty = false
    private var mutationsSinceForce = 0
    private var closed = false
    private val tokenOwnerMarker = Any()
    private var nonCapacityHeadChangeEpoch = 0L

    init {
        require(dataCapacityBytes >= MIN_DATA_CAPACITY_BYTES) {
            "Queue data capacity must be at least $MIN_DATA_CAPACITY_BYTES bytes."
        }
        queueFile.parentFile?.mkdirs()
        randomAccessFile = RandomAccessFile(queueFile, "rw")
        val expectedFileSize = HEADER_REGION_BYTES.toLong() + dataCapacityBytes
        val resetForSize = randomAccessFile.length() != expectedFileSize
        if (resetForSize) {
            randomAccessFile.setLength(expectedFileSize)
        }
        channel = randomAccessFile.channel
        mappedBuffer = channel.map(FileChannel.MapMode.READ_WRITE, 0, expectedFileSize)

        val header0 = if (resetForSize) null else readHeader(0)
        val header1 = if (resetForSize) null else readHeader(1)
        val candidates = listOfNotNull(
            header0?.let { 0 to it },
            header1?.let { 1 to it },
        ).sortedByDescending { it.second.generation }
        val selected = candidates.firstOrNull { (_, candidate) -> validateRecoveredMetadata(candidate) }

        if (selected == null) {
            val newestCandidate = candidates.firstOrNull()?.second
            metadata = Metadata.empty(dataCapacityBytes).apply {
                if (newestCandidate != null) {
                    generation = newestCandidate.generation + 1
                    corruptRecordCount = newestCandidate.corruptRecordCount + 1
                    droppedDueToCapacity = newestCandidate.droppedDueToCapacity
                    oversizedRecordCount = newestCandidate.oversizedRecordCount
                    nextSequence = newestCandidate.nextSequence
                }
            }
            writeHeader(0, metadata)
            writeHeader(1, metadata)
            mappedBuffer.force()
            stableHeaderSlot = 0
            workingHeaderSlot = 1
            if (!resetForSize) {
                diagnosticLogger("No fully consistent event queue header candidate was found; the queue was reset.")
            }
        } else {
            stableHeaderSlot = selected.first
            workingHeaderSlot = 1 - selected.first
            metadata = selected.second
            val newest = candidates.first()
            if (selected.first != newest.first) {
                metadata = metadata.copy(
                    generation = newest.second.generation + 1,
                    corruptRecordCount = metadata.corruptRecordCount + 1,
                )
                writeHeader(newest.first, metadata)
                mappedBuffer.force()
                stableHeaderSlot = newest.first
                workingHeaderSlot = selected.first
                diagnosticLogger(
                    "Recovered event queue from older consistent header metadata " +
                        "after rejecting generation ${newest.second.generation}.",
                )
            }
        }
    }

    suspend fun enqueue(capture: QueuedCapture): EnqueueResult {
        val payload = CaptureBinaryCodec.encode(capture)
        val recordSize = RECORD_HEADER_BYTES + payload.size
        return withContext(Dispatchers.IO) {
            mutex.withLock {
                ensureOpen()
                if (recordSize > dataCapacityBytes) {
                    metadata.oversizedRecordCount += 1
                    persistMetadataMutation()
                    return@withLock EnqueueResult.Oversized(recordSize, dataCapacityBytes)
                }

                var droppedCount = 0
                while (requiredBytesAtWritePosition(recordSize) > freeBytes()) {
                    if (metadata.recordCount == 0) {
                        normalizeEmptyQueue()
                        break
                    }
                    if (dropOldestForCapacity()) {
                        droppedCount += 1
                    }
                }

                val tailBytes = dataCapacityBytes - metadata.writePosition
                if (recordSize > tailBytes) {
                    writeWrapMarker(metadata.writePosition, tailBytes)
                    metadata.usedBytes += tailBytes
                    metadata.writePosition = 0
                }

                val sequence = metadata.nextSequence
                writeRecord(metadata.writePosition, sequence, payload)
                metadata.writePosition = (metadata.writePosition + recordSize) % dataCapacityBytes
                metadata.usedBytes += recordSize
                metadata.recordCount += 1
                metadata.nextSequence += 1
                metadata.droppedDueToCapacity += droppedCount.toLong()
                persistMetadataMutation()
                EnqueueResult.Enqueued(sequence, droppedCount)
            }
        }
    }

    suspend fun peekBatch(maxCount: Int): QueuedCaptureBatch {
        require(maxCount in 1..MAX_BATCH_COUNT) { "maxCount must be between 1 and $MAX_BATCH_COUNT." }
        return withContext(Dispatchers.IO) {
            mutex.withLock {
                ensureOpen()
                removeCorruptRecordsFromHead()
                if (metadata.recordCount == 0) {
                    return@withLock QueuedCaptureBatch(emptyList(), null)
                }

                val batchStart = metadata.readPosition
                var position = batchStart
                var remainingBytes = metadata.usedBytes
                val captures = ArrayList<QueuedCapture>(minOf(maxCount, metadata.recordCount))
                val sequences = ArrayList<Long>(minOf(maxCount, metadata.recordCount))

                while (captures.size < maxCount && sequences.size < metadata.recordCount && remainingBytes > 0) {
                    when (val entry = readEntry(position, remainingBytes, decodePayload = true)) {
                        is QueueEntry.Padding -> {
                            position = 0
                            remainingBytes -= entry.length
                        }
                        is QueueEntry.ValidRecord -> {
                            captures += entry.capture ?: break
                            sequences += entry.sequence
                            position = advance(position, entry.totalLength)
                            remainingBytes -= entry.totalLength
                        }
                        is QueueEntry.CorruptRecord,
                        QueueEntry.Unrecoverable,
                        -> break
                    }
                }

                QueuedCaptureBatch(
                    captures = captures,
                    acknowledgmentToken = if (captures.isEmpty()) {
                        null
                    } else {
                        QueueBatchToken(
                            readPosition = batchStart,
                            sequences = sequences.toList(),
                            droppedDueToCapacity = metadata.droppedDueToCapacity,
                            ownerMarker = tokenOwnerMarker,
                            nonCapacityHeadChangeEpoch = nonCapacityHeadChangeEpoch,
                        )
                    },
                )
            }
        }
    }

    suspend fun acknowledge(token: QueueBatchToken): AcknowledgeResult = withContext(Dispatchers.IO) {
        mutex.withLock {
            ensureOpen()
            if (token.ownerMarker !== tokenOwnerMarker ||
                token.nonCapacityHeadChangeEpoch != nonCapacityHeadChangeEpoch ||
                !hasContiguousSequences(token.sequences)
            ) {
                return@withLock AcknowledgeResult.Stale
            }

            val capacityEvictions = metadata.droppedDueToCapacity - token.droppedDueToCapacity
            if (capacityEvictions < 0) return@withLock AcknowledgeResult.Stale
            if (capacityEvictions >= token.sequences.size.toLong()) {
                return@withLock AcknowledgeResult.AlreadyEvicted
            }

            val evictedPrefixCount = capacityEvictions.toInt()
            if (evictedPrefixCount == 0 && token.readPosition != metadata.readPosition) {
                return@withLock AcknowledgeResult.Stale
            }
            val remainingSequences = token.sequences.subList(evictedPrefixCount, token.sequences.size)

            var position = metadata.readPosition
            var remainingBytes = metadata.usedBytes
            var consumedBytes = 0
            for (expectedSequence in remainingSequences) {
                while (true) {
                    when (val entry = readEntry(position, remainingBytes, decodePayload = false)) {
                        is QueueEntry.Padding -> {
                            position = 0
                            remainingBytes -= entry.length
                            consumedBytes += entry.length
                        }
                        is QueueEntry.ValidRecord -> {
                            if (entry.sequence != expectedSequence) {
                                return@withLock AcknowledgeResult.Stale
                            }
                            position = advance(position, entry.totalLength)
                            remainingBytes -= entry.totalLength
                            consumedBytes += entry.totalLength
                            break
                        }
                        is QueueEntry.CorruptRecord,
                        QueueEntry.Unrecoverable,
                        -> return@withLock AcknowledgeResult.Stale
                    }
                }
            }

            metadata.readPosition = position
            metadata.usedBytes -= consumedBytes
            metadata.recordCount -= remainingSequences.size
            if (metadata.recordCount == 0) {
                normalizeEmptyQueue()
            }
            nonCapacityHeadChangeEpoch += 1
            persistMetadataMutation()
            if (evictedPrefixCount == 0) {
                AcknowledgeResult.FullyAcknowledged
            } else {
                AcknowledgeResult.RemainingSuffixAcknowledged(evictedPrefixCount)
            }
        }
    }

    suspend fun pendingCount(): Int = withContext(Dispatchers.IO) {
        mutex.withLock {
            ensureOpen()
            countReadableRecords()
        }
    }

    suspend fun isEmpty(): Boolean = pendingCount() == 0

    suspend fun diagnostics(): QueueDiagnostics = withContext(Dispatchers.IO) {
        mutex.withLock {
            ensureOpen()
            QueueDiagnostics(
                pendingRecordCount = countReadableRecords(),
                droppedDueToCapacity = metadata.droppedDueToCapacity,
                corruptRecordCount = metadata.corruptRecordCount,
                oversizedRecordCount = metadata.oversizedRecordCount,
            )
        }
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        mutex.withLock {
            ensureOpen()
            normalizeEmptyQueue()
            nonCapacityHeadChangeEpoch += 1
            persistMetadataMutation()
            checkpointInternal()
        }
    }

    suspend fun checkpoint() = withContext(Dispatchers.IO) {
        mutex.withLock {
            ensureOpen()
            checkpointInternal()
        }
    }

    suspend fun close() = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (closed) return@withLock
            try {
                checkpointInternal()
            } finally {
                closed = true
                try {
                    channel.close()
                } finally {
                    try {
                        randomAccessFile.close()
                    } finally {
                        releaseOwnership(ownershipKey)
                    }
                }
            }
        }
    }

    private fun hasContiguousSequences(sequences: List<Long>): Boolean {
        if (sequences.isEmpty() || sequences.first() < 1) return false
        return sequences.zipWithNext().all { (previous, next) ->
            previous != Long.MAX_VALUE && next == previous + 1
        }
    }

    private fun removeCorruptRecordsFromHead() {
        var changed = false
        while (metadata.recordCount > 0 && metadata.usedBytes > 0) {
            when (val entry = readEntry(metadata.readPosition, metadata.usedBytes, decodePayload = true)) {
                is QueueEntry.Padding -> {
                    consumeHeadBytes(entry.length, record = false)
                    changed = true
                }
                is QueueEntry.ValidRecord -> break
                is QueueEntry.CorruptRecord -> {
                    consumeHeadBytes(entry.totalLength, record = true)
                    metadata.corruptRecordCount += 1
                    nonCapacityHeadChangeEpoch += 1
                    changed = true
                }
                QueueEntry.Unrecoverable -> {
                    metadata.corruptRecordCount += 1
                    diagnosticLogger("Event queue contained an unrecoverable record header and was reset.")
                    normalizeEmptyQueue()
                    nonCapacityHeadChangeEpoch += 1
                    changed = true
                }
            }
        }
        if (changed) persistMetadataMutation()
    }

    private fun countReadableRecords(): Int {
        var position = metadata.readPosition
        var remainingBytes = metadata.usedBytes
        var physicalRecords = 0
        var validRecords = 0
        while (physicalRecords < metadata.recordCount && remainingBytes > 0) {
            when (val entry = readEntry(position, remainingBytes, decodePayload = true)) {
                is QueueEntry.Padding -> {
                    position = 0
                    remainingBytes -= entry.length
                }
                is QueueEntry.ValidRecord -> {
                    validRecords += 1
                    physicalRecords += 1
                    position = advance(position, entry.totalLength)
                    remainingBytes -= entry.totalLength
                }
                is QueueEntry.CorruptRecord -> {
                    physicalRecords += 1
                    position = advance(position, entry.totalLength)
                    remainingBytes -= entry.totalLength
                }
                QueueEntry.Unrecoverable -> break
            }
        }
        return validRecords
    }

    private fun validateRecoveredMetadata(candidate: Metadata): Boolean {
        if (candidate.recordCount == 0) {
            return candidate.usedBytes == 0 && candidate.readPosition == candidate.writePosition
        }
        var position = candidate.readPosition
        var remainingBytes = candidate.usedBytes
        var physicalRecords = 0
        var previousSequence = 0L
        while (physicalRecords < candidate.recordCount && remainingBytes > 0) {
            when (val entry = readEntry(position, remainingBytes, decodePayload = false)) {
                is QueueEntry.Padding -> {
                    position = 0
                    remainingBytes -= entry.length
                }
                is QueueEntry.ValidRecord -> {
                    if (entry.sequence <= previousSequence ||
                        (physicalRecords > 0 && entry.sequence != previousSequence + 1) ||
                        entry.sequence >= candidate.nextSequence
                    ) return false
                    previousSequence = entry.sequence
                    physicalRecords += 1
                    position = advance(position, entry.totalLength)
                    remainingBytes -= entry.totalLength
                }
                is QueueEntry.CorruptRecord -> {
                    val sequence = entry.sequence ?: return false
                    if (sequence <= previousSequence ||
                        (physicalRecords > 0 && sequence != previousSequence + 1) ||
                        sequence >= candidate.nextSequence
                    ) return false
                    previousSequence = sequence
                    physicalRecords += 1
                    position = advance(position, entry.totalLength)
                    remainingBytes -= entry.totalLength
                }
                QueueEntry.Unrecoverable -> return false
            }
        }
        return physicalRecords == candidate.recordCount &&
            remainingBytes == 0 &&
            position == candidate.writePosition &&
            previousSequence == candidate.nextSequence - 1
    }

    private fun dropOldestForCapacity(): Boolean {
        while (metadata.usedBytes > 0) {
            when (val entry = readEntry(metadata.readPosition, metadata.usedBytes, decodePayload = false)) {
                is QueueEntry.Padding -> consumeHeadBytes(entry.length, record = false)
                is QueueEntry.ValidRecord -> {
                    consumeHeadBytes(entry.totalLength, record = true)
                    return true
                }
                is QueueEntry.CorruptRecord -> {
                    consumeHeadBytes(entry.totalLength, record = true)
                    metadata.corruptRecordCount += 1
                    return true
                }
                QueueEntry.Unrecoverable -> {
                    metadata.corruptRecordCount += 1
                    diagnosticLogger("Event queue was reset while making capacity after corrupt metadata.")
                    normalizeEmptyQueue()
                    return false
                }
            }
        }
        normalizeEmptyQueue()
        return false
    }

    private fun consumeHeadBytes(length: Int, record: Boolean) {
        metadata.readPosition = advance(metadata.readPosition, length)
        metadata.usedBytes -= length
        if (record) metadata.recordCount -= 1
        if (metadata.recordCount == 0) normalizeEmptyQueue()
    }

    private fun readEntry(position: Int, availableBytes: Int, decodePayload: Boolean): QueueEntry {
        if (availableBytes <= 0 || position !in 0 until dataCapacityBytes) return QueueEntry.Unrecoverable
        val tailBytes = dataCapacityBytes - position
        if (tailBytes < WRAP_HEADER_BYTES) return QueueEntry.Padding(tailBytes)

        val buffer = dataBufferAt(position)
        return when (buffer.int) {
            WRAP_MAGIC -> {
                val paddingLength = buffer.int
                if (paddingLength == tailBytes && paddingLength <= availableBytes) {
                    QueueEntry.Padding(paddingLength)
                } else {
                    QueueEntry.Unrecoverable
                }
            }
            RECORD_MAGIC -> readRecordEntry(buffer, tailBytes, availableBytes, decodePayload)
            else -> QueueEntry.Unrecoverable
        }
    }

    private fun readRecordEntry(
        buffer: ByteBuffer,
        tailBytes: Int,
        availableBytes: Int,
        decodePayload: Boolean,
    ): QueueEntry {
        if (tailBytes < RECORD_HEADER_BYTES || availableBytes < RECORD_HEADER_BYTES) {
            return QueueEntry.Unrecoverable
        }
        val version = buffer.int
        val totalLength = buffer.int
        val payloadLength = buffer.int
        val expectedCrc = buffer.int
        buffer.int // reserved
        val sequence = buffer.long
        val headerIsValid = version == RECORD_VERSION &&
            payloadLength in 0..CaptureBinaryCodec.MAX_PAYLOAD_BYTES &&
            totalLength == RECORD_HEADER_BYTES + payloadLength &&
            totalLength <= tailBytes &&
            totalLength <= availableBytes
        if (!headerIsValid) return QueueEntry.Unrecoverable

        val payload = ByteArray(payloadLength)
        buffer.get(payload)
        if (crc32(payload) != expectedCrc) {
            return QueueEntry.CorruptRecord(totalLength, sequence)
        }
        if (!decodePayload) {
            return QueueEntry.ValidRecord(totalLength, sequence, null)
        }
        val capture = try {
            CaptureBinaryCodec.decode(payload)
        } catch (_: CaptureCodecException) {
            return QueueEntry.CorruptRecord(totalLength, sequence)
        }
        return QueueEntry.ValidRecord(totalLength, sequence, capture)
    }

    private fun writeRecord(position: Int, sequence: Long, payload: ByteArray) {
        val totalLength = RECORD_HEADER_BYTES + payload.size
        val record = ByteBuffer.allocate(totalLength).order(ByteOrder.BIG_ENDIAN)
            .putInt(RECORD_MAGIC)
            .putInt(RECORD_VERSION)
            .putInt(totalLength)
            .putInt(payload.size)
            .putInt(crc32(payload))
            .putInt(0)
            .putLong(sequence)
            .put(payload)
            .array()
        dataBufferAt(position).put(record)
    }

    private fun writeWrapMarker(position: Int, tailBytes: Int) {
        if (tailBytes >= WRAP_HEADER_BYTES) {
            dataBufferAt(position)
                .putInt(WRAP_MAGIC)
                .putInt(tailBytes)
        }
    }

    private fun requiredBytesAtWritePosition(recordSize: Int): Int {
        val tailBytes = dataCapacityBytes - metadata.writePosition
        return recordSize + if (recordSize > tailBytes) tailBytes else 0
    }

    private fun freeBytes(): Int = dataCapacityBytes - metadata.usedBytes

    private fun normalizeEmptyQueue() {
        metadata.readPosition = 0
        metadata.writePosition = 0
        metadata.recordCount = 0
        metadata.usedBytes = 0
    }

    private fun persistMetadataMutation() {
        metadata.generation += 1
        writeHeader(workingHeaderSlot, metadata)
        workingHeaderDirty = true
        mutationsSinceForce += 1
        if (mutationsSinceForce >= FORCE_EVERY_MUTATIONS) {
            checkpointInternal()
        }
    }

    private fun checkpointInternal() {
        mappedBuffer.force()
        if (workingHeaderDirty) {
            stableHeaderSlot = workingHeaderSlot
            workingHeaderSlot = 1 - stableHeaderSlot
            workingHeaderDirty = false
        }
        mutationsSinceForce = 0
    }

    private fun writeHeader(slot: Int, value: Metadata) {
        val bytes = ByteArray(HEADER_SLOT_BYTES)
        val header = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        header.putInt(FILE_MAGIC)
        header.putInt(FILE_VERSION)
        header.putInt(HEADER_SLOT_BYTES)
        header.putInt(dataCapacityBytes)
        header.putLong(value.generation)
        header.putInt(value.readPosition)
        header.putInt(value.writePosition)
        header.putInt(value.recordCount)
        header.putInt(value.usedBytes)
        header.putLong(value.droppedDueToCapacity)
        header.putLong(value.corruptRecordCount)
        header.putLong(value.oversizedRecordCount)
        header.putLong(value.nextSequence)
        header.putInt(crc32(bytes, 0, HEADER_CRC_OFFSET))
        val destination = mappedBuffer.duplicate()
        destination.position(slot * HEADER_SLOT_BYTES)
        destination.put(bytes)
    }

    private fun readHeader(slot: Int): Metadata? {
        val bytes = ByteArray(HEADER_SLOT_BYTES)
        val source = mappedBuffer.duplicate()
        source.position(slot * HEADER_SLOT_BYTES)
        source.get(bytes)
        val header = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        if (header.int != FILE_MAGIC || header.int != FILE_VERSION) return null
        if (header.int != HEADER_SLOT_BYTES || header.int != dataCapacityBytes) return null
        val generation = header.long
        val readPosition = header.int
        val writePosition = header.int
        val recordCount = header.int
        val usedBytes = header.int
        val dropped = header.long
        val corrupt = header.long
        val oversized = header.long
        val nextSequence = header.long
        val storedCrc = header.int
        if (storedCrc != crc32(bytes, 0, HEADER_CRC_OFFSET)) return null
        if (generation < 0 ||
            readPosition !in 0 until dataCapacityBytes ||
            writePosition !in 0 until dataCapacityBytes ||
            recordCount !in 0..(dataCapacityBytes / RECORD_HEADER_BYTES) ||
            usedBytes !in 0..dataCapacityBytes ||
            dropped < 0 || corrupt < 0 || oversized < 0 || nextSequence < 1
        ) return null
        if (recordCount == 0 && usedBytes != 0) return null
        return Metadata(
            generation = generation,
            readPosition = readPosition,
            writePosition = writePosition,
            recordCount = recordCount,
            usedBytes = usedBytes,
            droppedDueToCapacity = dropped,
            corruptRecordCount = corrupt,
            oversizedRecordCount = oversized,
            nextSequence = nextSequence,
        )
    }

    private fun dataBufferAt(dataPosition: Int): ByteBuffer {
        val duplicate = mappedBuffer.duplicate().order(ByteOrder.BIG_ENDIAN)
        duplicate.position(HEADER_REGION_BYTES + dataPosition)
        return duplicate.slice().order(ByteOrder.BIG_ENDIAN)
    }

    private fun advance(position: Int, byteCount: Int): Int = (position + byteCount) % dataCapacityBytes

    private fun ensureOpen() {
        check(!closed) { "Event queue is closed." }
    }

    private fun crc32(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size): Int {
        return CRC32().apply { update(bytes, offset, length) }.value.toInt()
    }

    private data class Metadata(
        var generation: Long,
        var readPosition: Int,
        var writePosition: Int,
        var recordCount: Int,
        var usedBytes: Int,
        var droppedDueToCapacity: Long,
        var corruptRecordCount: Long,
        var oversizedRecordCount: Long,
        var nextSequence: Long,
    ) {
        companion object {
            fun empty(dataCapacityBytes: Int): Metadata {
                require(dataCapacityBytes > 0)
                return Metadata(0, 0, 0, 0, 0, 0, 0, 0, 1)
            }
        }
    }

    private sealed interface QueueEntry {
        data class Padding(val length: Int) : QueueEntry
        data class ValidRecord(
            val totalLength: Int,
            val sequence: Long,
            val capture: QueuedCapture?,
        ) : QueueEntry
        data class CorruptRecord(val totalLength: Int, val sequence: Long?) : QueueEntry
        data object Unrecoverable : QueueEntry
    }

    companion object {
        const val DEFAULT_DATA_CAPACITY_BYTES = 4 * 1024 * 1024
        const val QUEUE_FILE_NAME = "pending_captures.ring"
        private const val TAG = "PersistentEventQueue"
        private const val QUEUE_DIRECTORY_NAME = "event_queue"
        private const val MIN_DATA_CAPACITY_BYTES = 512
        private const val MAX_BATCH_COUNT = 1_000
        private const val FORCE_EVERY_MUTATIONS = 16
        internal const val HEADER_SLOT_BYTES = 128
        private const val HEADER_SLOT_COUNT = 2
        internal const val HEADER_REGION_BYTES = HEADER_SLOT_BYTES * HEADER_SLOT_COUNT
        private const val HEADER_CRC_OFFSET = 72
        private const val FILE_MAGIC = 0x45565132 // EVQ2
        private const val FILE_VERSION = 1
        internal const val RECORD_MAGIC = 0x45565231 // EVR1
        private const val WRAP_MAGIC = 0x57524150 // WRAP
        private const val RECORD_VERSION = 1
        internal const val RECORD_HEADER_BYTES = 32
        private const val WRAP_HEADER_BYTES = 8
        private val ownershipMonitor = Any()
        private val ownedQueuePaths = mutableSetOf<String>()

        suspend fun open(
            context: Context,
            dataCapacityBytes: Int = DEFAULT_DATA_CAPACITY_BYTES,
        ): PersistentEventQueue = withContext(Dispatchers.IO) {
            val directory = File(context.noBackupFilesDir, QUEUE_DIRECTORY_NAME)
            openOwned(File(directory, QUEUE_FILE_NAME), dataCapacityBytes) { message -> Log.w(TAG, message) }
        }

        internal suspend fun openFile(
            file: File,
            dataCapacityBytes: Int,
            diagnosticLogger: (String) -> Unit = {},
        ): PersistentEventQueue = withContext(Dispatchers.IO) {
            openOwned(file, dataCapacityBytes, diagnosticLogger)
        }

        private fun openOwned(
            file: File,
            dataCapacityBytes: Int,
            diagnosticLogger: (String) -> Unit,
        ): PersistentEventQueue {
            val ownershipKey = file.canonicalFile.path
            synchronized(ownershipMonitor) {
                check(ownedQueuePaths.add(ownershipKey)) {
                    "An event queue for this file is already open in this process."
                }
            }
            return try {
                PersistentEventQueue(file, ownershipKey, dataCapacityBytes, diagnosticLogger)
            } catch (error: Throwable) {
                releaseOwnership(ownershipKey)
                throw error
            }
        }

        private fun releaseOwnership(ownershipKey: String) {
            synchronized(ownershipMonitor) {
                ownedQueuePaths.remove(ownershipKey)
            }
        }
    }
}
