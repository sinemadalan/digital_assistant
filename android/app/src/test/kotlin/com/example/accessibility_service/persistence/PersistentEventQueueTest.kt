package com.example.accessibility_service.persistence

import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PersistentEventQueueTest {
    @Test
    fun emptyQueueHasNoBatchAndIsEmpty() = runTest {
        val queue = newQueue()
        assertTrue(queue.isEmpty())
        assertEquals(0, queue.currentPhysicalRecordCount())
        assertEquals(0, queue.pendingCount())
        assertTrue(queue.peekBatch(10).captures.isEmpty())
        queue.close()
    }

    @Test
    fun singleEnqueueCanBePeekedWithoutDeletion() = runTest {
        val queue = newQueue()
        val capture = capture(1)
        val result = queue.enqueue(capture) as EnqueueResult.Enqueued

        assertEquals(1, result.pendingPhysicalRecordCount)
        assertEquals(1, queue.currentPhysicalRecordCount())
        assertEquals(listOf(capture), queue.peekBatch(1).captures)
        assertEquals(listOf(capture), queue.peekBatch(1).captures)
        assertEquals(1, queue.pendingCount())
        queue.close()
    }

    @Test
    fun multipleEnqueuePreservesFifoOrder() = runTest {
        val queue = newQueue()
        val expected = (1..5).map(::capture)
        expected.forEach { queue.enqueue(it) }

        assertEquals(5, queue.currentPhysicalRecordCount())
        assertEquals(expected, queue.peekBatch(10).captures)
        queue.close()
    }

    @Test
    fun acknowledgeConsumesOnlyPeekedRecords() = runTest {
        val queue = newQueue()
        (1..4).forEach { queue.enqueue(capture(it)) }
        val batch = queue.peekBatch(2)

        assertEquals(
            AcknowledgeResult.FullyAcknowledged,
            queue.acknowledge(requireNotNull(batch.acknowledgmentToken)),
        )
        assertEquals(2, queue.currentPhysicalRecordCount())
        assertEquals(listOf(capture(3), capture(4)), queue.peekBatch(10).captures)
        assertEquals(2, queue.pendingCount())
        queue.close()
    }

    @Test
    fun unacknowledgedRecordsSurviveReopen() = runTest {
        val file = newFile()
        var queue = open(file)
        queue.enqueue(capture(1))
        queue.enqueue(capture(2))
        queue.checkpoint()
        queue.close()

        queue = open(file)
        assertEquals(2, queue.currentPhysicalRecordCount())
        assertEquals(listOf(capture(1), capture(2)), queue.peekBatch(10).captures)
        queue.close()
    }

    @Test
    fun acknowledgedRecordsStayConsumedAfterReopen() = runTest {
        val file = newFile()
        var queue = open(file)
        (1..3).forEach { queue.enqueue(capture(it)) }
        val batch = queue.peekBatch(2)
        queue.acknowledge(requireNotNull(batch.acknowledgmentToken))
        queue.close()

        queue = open(file)
        assertEquals(listOf(capture(3)), queue.peekBatch(10).captures)
        queue.close()
    }

    @Test
    fun recordWrapsAtDataRegionBoundary() = runTest {
        val queue = newQueue(capacity = SMALL_CAPACITY)
        val recordBytes = PersistentEventQueue.RECORD_HEADER_BYTES + CaptureBinaryCodec.encode(capture(1)).size
        val initialCount = SMALL_CAPACITY / recordBytes
        (1..initialCount).forEach { queue.enqueue(capture(it)) }
        val acknowledged = queue.peekBatch(initialCount - 1)
        queue.acknowledge(requireNotNull(acknowledged.acknowledgmentToken))
        queue.enqueue(capture(99))

        assertEquals(2, queue.currentPhysicalRecordCount())
        assertEquals(listOf(capture(initialCount), capture(99)), queue.peekBatch(10).captures)
        queue.close()
    }

    @Test
    fun recordsCanEndExactlyAtDataRegionBoundary() = runTest {
        val recordBytes = PersistentEventQueue.RECORD_HEADER_BYTES + CaptureBinaryCodec.encode(capture(1)).size
        val recordCount = (512 + recordBytes - 1) / recordBytes
        val exactCapacity = recordBytes * recordCount
        val queue = newQueue(capacity = exactCapacity)
        val expected = (1..recordCount).map(::capture)
        expected.forEach { queue.enqueue(it) }

        assertEquals(expected, queue.peekBatch(100).captures)
        assertEquals(0, queue.diagnostics().droppedDueToCapacity)
        queue.close()
    }

    @Test
    fun manyWrapCyclesDoNotCorruptQueue() = runTest {
        val queue = newQueue(capacity = SMALL_CAPACITY)
        repeat(50) { cycle ->
            repeat(8) { index -> queue.enqueue(capture(cycle * 10 + index)) }
            val batch = queue.peekBatch(100)
            if (batch.acknowledgmentToken != null) queue.acknowledge(batch.acknowledgmentToken)
        }

        assertTrue(queue.isEmpty())
        assertEquals(0, queue.diagnostics().corruptRecordCount)
        queue.close()
    }

    @Test
    fun fullCapacityDropsOldestAndAcceptsNewest() = runTest {
        val queue = newQueue(capacity = SMALL_CAPACITY)
        repeat(20) { queue.enqueue(capture(it)) }
        val remaining = queue.peekBatch(100).captures

        assertEquals(remaining.size, queue.currentPhysicalRecordCount())
        assertTrue(remaining.first().packageName != capture(0).packageName)
        assertEquals(capture(19), remaining.last())
        assertTrue(queue.diagnostics().droppedDueToCapacity > 0)
        queue.close()
    }

    @Test
    fun oversizedRecordIsRejectedWithoutDamagingQueue() = runTest {
        val queue = newQueue(capacity = SMALL_CAPACITY)
        queue.enqueue(capture(1))
        val oversized = capture(2).copy(screenText = listOf("x".repeat(800)))

        assertTrue(queue.enqueue(oversized) is EnqueueResult.Oversized)
        assertEquals(listOf(capture(1)), queue.peekBatch(10).captures)
        assertEquals(1, queue.diagnostics().oversizedRecordCount)
        queue.close()
    }

    @Test
    fun corruptedRecordCrcIsDroppedSafely() = runTest {
        val file = newFile()
        var queue = open(file)
        queue.enqueue(capture(1))
        queue.enqueue(capture(2))
        queue.checkpoint()
        queue.close()
        RandomAccessFile(file, "rw").use { random ->
            val payloadOffset = PersistentEventQueue.HEADER_REGION_BYTES + PersistentEventQueue.RECORD_HEADER_BYTES
            random.seek(payloadOffset.toLong())
            random.write(random.read() xor 0xFF)
        }

        queue = open(file)
        assertEquals(2, queue.currentPhysicalRecordCount())
        assertEquals(1, queue.pendingCount())
        assertEquals(listOf(capture(2)), queue.peekBatch(10).captures)
        assertEquals(1, queue.currentPhysicalRecordCount())
        assertEquals(1, queue.diagnostics().corruptRecordCount)
        queue.close()
    }

    @Test
    fun malformedRecordHeaderResetsSafely() = runTest {
        val file = newFile()
        var queue = open(file)
        queue.enqueue(capture(1))
        queue.checkpoint()
        queue.close()
        RandomAccessFile(file, "rw").use { random ->
            random.seek((PersistentEventQueue.HEADER_REGION_BYTES + 8).toLong())
            random.writeInt(Int.MAX_VALUE)
        }

        queue = open(file)
        assertTrue(queue.peekBatch(10).captures.isEmpty())
        assertTrue(queue.diagnostics().corruptRecordCount >= 1)
        queue.close()
    }

    @Test
    fun invalidDualHeaderInitializesEmptyQueue() = runTest {
        val file = newFile()
        var queue = open(file)
        queue.enqueue(capture(1))
        queue.close()
        RandomAccessFile(file, "rw").use { random ->
            random.seek(0)
            random.write(ByteArray(PersistentEventQueue.HEADER_REGION_BYTES))
        }

        queue = open(file)
        assertTrue(queue.isEmpty())
        queue.close()
    }

    @Test
    fun unsupportedHeaderVersionInitializesEmptyQueue() = runTest {
        val file = newFile()
        var queue = open(file)
        queue.enqueue(capture(1))
        queue.close()
        RandomAccessFile(file, "rw").use { random ->
            random.seek(4)
            random.writeInt(99)
            random.seek(128 + 4L)
            random.writeInt(99)
        }

        queue = open(file)
        assertTrue(queue.isEmpty())
        queue.close()
    }

    @Test
    fun clearIsIdempotent() = runTest {
        val queue = newQueue()
        queue.enqueue(capture(1))
        queue.clear()
        queue.clear()

        assertTrue(queue.isEmpty())
        queue.close()
    }

    @Test
    fun staleAcknowledgeCannotConsumeNewRecords() = runTest {
        val queue = newQueue(capacity = SMALL_CAPACITY)
        queue.enqueue(capture(1))
        val staleToken = requireNotNull(queue.peekBatch(1).acknowledgmentToken)
        repeat(20) { queue.enqueue(capture(100 + it)) }

        assertEquals(AcknowledgeResult.AlreadyEvicted, queue.acknowledge(staleToken))
        assertTrue(queue.pendingCount() > 0)
        queue.close()
    }

    @Test
    fun prefixCapacityEvictionAcknowledgesRemainingBatchSuffix() = runTest {
        val recordBytes = ringRecordBytes()
        val queue = newQueue(capacity = recordBytes * 5)
        (1..5).forEach { queue.enqueue(ringCapture(it)) }
        val token = requireNotNull(queue.peekBatch(5).acknowledgmentToken)
        queue.enqueue(ringCapture(6))
        queue.enqueue(ringCapture(7))

        assertEquals(
            AcknowledgeResult.RemainingSuffixAcknowledged(evictedPrefixCount = 2),
            queue.acknowledge(token),
        )
        assertEquals(listOf(ringCapture(6), ringCapture(7)), queue.peekBatch(10).captures)
        queue.close()
    }

    @Test
    fun entirelyEvictedBatchDoesNotConsumeNewRecords() = runTest {
        val recordBytes = ringRecordBytes()
        val queue = newQueue(capacity = recordBytes * 5)
        (1..5).forEach { queue.enqueue(ringCapture(it)) }
        val token = requireNotNull(queue.peekBatch(3).acknowledgmentToken)
        (6..8).forEach { queue.enqueue(ringCapture(it)) }
        val before = queue.peekBatch(10).captures

        assertEquals(AcknowledgeResult.AlreadyEvicted, queue.acknowledge(token))
        assertEquals(before, queue.peekBatch(10).captures)
        queue.close()
    }

    @Test
    fun acknowledgeRejectsHeadOutsideTokenSequence() = runTest {
        val queue = newQueue()
        (1..3).forEach { queue.enqueue(ringCapture(it)) }
        val valid = requireNotNull(queue.peekBatch(2).acknowledgmentToken)
        val invalid = QueueBatchToken(
            readPosition = valid.readPosition,
            sequences = listOf(valid.sequences.first() + 100),
            droppedDueToCapacity = valid.droppedDueToCapacity,
            ownerMarker = valid.ownerMarker,
            nonCapacityHeadChangeEpoch = valid.nonCapacityHeadChangeEpoch,
        )
        val before = queue.peekBatch(10).captures

        assertEquals(AcknowledgeResult.Stale, queue.acknowledge(invalid))
        assertEquals(before, queue.peekBatch(10).captures)
        queue.close()
    }

    @Test
    fun acknowledgeRejectsGapInsideTokenSequences() = runTest {
        val queue = newQueue()
        (1..4).forEach { queue.enqueue(ringCapture(it)) }
        val valid = requireNotNull(queue.peekBatch(3).acknowledgmentToken)
        val invalid = QueueBatchToken(
            readPosition = valid.readPosition,
            sequences = listOf(valid.sequences[0], valid.sequences[2]),
            droppedDueToCapacity = valid.droppedDueToCapacity,
            ownerMarker = valid.ownerMarker,
            nonCapacityHeadChangeEpoch = valid.nonCapacityHeadChangeEpoch,
        )
        val before = queue.peekBatch(10).captures

        assertEquals(AcknowledgeResult.Stale, queue.acknowledge(invalid))
        assertEquals(before, queue.peekBatch(10).captures)
        queue.close()
    }

    @Test
    fun oldTokenAfterAnotherAcknowledgeCannotConsumeNewRecords() = runTest {
        val queue = newQueue()
        (1..4).forEach { queue.enqueue(ringCapture(it)) }
        val oldToken = requireNotNull(queue.peekBatch(2).acknowledgmentToken)
        assertEquals(AcknowledgeResult.FullyAcknowledged, queue.acknowledge(oldToken))
        val before = queue.peekBatch(10).captures

        assertEquals(AcknowledgeResult.Stale, queue.acknowledge(oldToken))
        assertEquals(before, queue.peekBatch(10).captures)
        queue.close()
    }

    @Test
    fun capacityEvictionAcrossWrapAcknowledgesRemainingSuffix() = runTest {
        val recordBytes = ringRecordBytes()
        val queue = newQueue(capacity = recordBytes * 5)
        (1..5).forEach { queue.enqueue(ringCapture(it)) }
        val initial = queue.peekBatch(3)
        queue.acknowledge(requireNotNull(initial.acknowledgmentToken))
        (6..8).forEach { queue.enqueue(ringCapture(it)) }
        val wrappedToken = requireNotNull(queue.peekBatch(5).acknowledgmentToken)
        queue.enqueue(ringCapture(9))
        queue.enqueue(ringCapture(10))

        assertEquals(
            AcknowledgeResult.RemainingSuffixAcknowledged(evictedPrefixCount = 2),
            queue.acknowledge(wrappedToken),
        )
        assertEquals(listOf(ringCapture(9), ringCapture(10)), queue.peekBatch(10).captures)
        queue.close()
    }

    @Test
    fun newestConsistentHeaderIsPreferredOverOlderHeader() = runTest {
        val file = queueWithTwoHeaderGenerations()
        val queue = open(file)

        assertEquals(listOf(ringCapture(1), ringCapture(2)), queue.peekBatch(10).captures)
        queue.close()
    }

    @Test
    fun inconsistentNewestHeaderFallsBackToOlderConsistentHeader() = runTest {
        val file = queueWithTwoHeaderGenerations()
        corruptRecordTotalLength(file, recordIndex = 1)
        val logs = mutableListOf<String>()
        val queue = open(file, diagnosticLogger = logs::add)

        assertEquals(1, queue.currentPhysicalRecordCount())
        assertEquals(listOf(ringCapture(1)), queue.peekBatch(10).captures)
        assertEquals(1, queue.pendingCount())
        assertTrue(logs.any { it.contains("older consistent header metadata") })
        queue.close()
    }

    @Test
    fun invalidNewestHeaderCrcUsesOlderHeader() = runTest {
        val file = queueWithTwoHeaderGenerations()
        RandomAccessFile(file, "rw").use { random ->
            random.seek(HEADER_CRC_OFFSET.toLong())
            val storedCrc = random.readInt()
            random.seek(HEADER_CRC_OFFSET.toLong())
            random.writeInt(storedCrc xor -0x80000000)
        }
        val queue = open(file)

        assertEquals(listOf(ringCapture(1)), queue.peekBatch(10).captures)
        queue.close()
    }

    @Test
    fun twoMetadataValidButDataInconsistentHeadersResetConservatively() = runTest {
        val file = queueWithTwoHeaderGenerations()
        corruptRecordTotalLength(file, recordIndex = 0)
        val queue = open(file)

        assertTrue(queue.isEmpty())
        assertTrue(queue.diagnostics().corruptRecordCount >= 1)
        queue.close()
    }

    @Test
    fun fallbackPreservesOlderHeaderFifoAndPendingRecords() = runTest {
        val file = newFile()
        var queue = open(file)
        queue.enqueue(ringCapture(1))
        queue.enqueue(ringCapture(2))
        queue.checkpoint()
        queue.enqueue(ringCapture(3))
        queue.close()
        corruptRecordTotalLength(file, recordIndex = 2)

        queue = open(file)
        assertEquals(listOf(ringCapture(1), ringCapture(2)), queue.peekBatch(10).captures)
        assertEquals(2, queue.pendingCount())
        queue.close()
    }

    @Test
    fun secondOpenForSamePathIsRejected() = runTest {
        val file = newFile()
        val first = open(file)

        val error = try {
            open(file)
            null
        } catch (caught: IllegalStateException) {
            caught
        }
        assertNotNull(error)
        first.close()
    }

    @Test
    fun closeReleasesPathForReopen() = runTest {
        val file = newFile()
        open(file).close()

        val reopened = open(file)
        reopened.enqueue(ringCapture(1))
        assertEquals(1, reopened.pendingCount())
        reopened.close()
    }

    @Test
    fun differentQueuePathsCanBeOpenTogether() = runTest {
        val first = open(newFile())
        val second = open(newFile())

        first.enqueue(ringCapture(1))
        second.enqueue(ringCapture(2))
        assertEquals(1, first.pendingCount())
        assertEquals(1, second.pendingCount())
        first.close()
        second.close()
    }

    @Test
    fun fixedFileSizeDoesNotGrow() = runTest {
        val file = newFile()
        val queue = open(file, SMALL_CAPACITY)
        repeat(100) { queue.enqueue(capture(it)) }
        queue.close()

        assertEquals(
            (PersistentEventQueue.HEADER_REGION_BYTES + SMALL_CAPACITY).toLong(),
            file.length(),
        )
    }

    @Test
    fun concurrentEnqueueOperationsAreSerialized() = runTest {
        val queue = newQueue(capacity = 64 * 1024)
        (0 until 100).map { index ->
            async(Dispatchers.Default) { queue.enqueue(capture(index)) }
        }.awaitAll()

        assertEquals(100, queue.pendingCount())
        assertEquals(0, queue.diagnostics().corruptRecordCount)
        queue.close()
    }

    @Test
    fun concurrentEnqueuePeekAndAcknowledgeRemainConsistent() = runTest {
        val queue = newQueue(capacity = 64 * 1024)
        repeat(20) { queue.enqueue(capture(it)) }
        val consumer = async(Dispatchers.Default) {
            val batch = queue.peekBatch(10)
            assertNotNull(batch.acknowledgmentToken)
            queue.acknowledge(requireNotNull(batch.acknowledgmentToken))
        }
        val producer = async(Dispatchers.Default) {
            repeat(20) { queue.enqueue(capture(100 + it)) }
        }
        awaitAll(consumer, producer)

        assertEquals(30, queue.pendingCount())
        assertEquals(0, queue.diagnostics().corruptRecordCount)
        queue.close()
    }

    private suspend fun newQueue(capacity: Int = DEFAULT_TEST_CAPACITY): PersistentEventQueue {
        return open(newFile(), capacity)
    }

    private suspend fun open(
        file: File,
        capacity: Int = DEFAULT_TEST_CAPACITY,
        diagnosticLogger: (String) -> Unit = {},
    ): PersistentEventQueue = PersistentEventQueue.openFile(file, capacity, diagnosticLogger)

    private suspend fun queueWithTwoHeaderGenerations(): File {
        val file = newFile()
        val queue = open(file)
        queue.enqueue(ringCapture(1))
        queue.checkpoint()
        queue.enqueue(ringCapture(2))
        queue.close()
        return file
    }

    private fun corruptRecordTotalLength(file: File, recordIndex: Int) {
        val recordOffset = PersistentEventQueue.HEADER_REGION_BYTES + ringRecordBytes() * recordIndex
        RandomAccessFile(file, "rw").use { random ->
            random.seek((recordOffset + RECORD_TOTAL_LENGTH_OFFSET).toLong())
            random.writeInt(Int.MAX_VALUE)
        }
    }

    private fun ringRecordBytes(): Int =
        PersistentEventQueue.RECORD_HEADER_BYTES + CaptureBinaryCodec.encode(ringCapture(1)).size

    private fun ringCapture(id: Int): QueuedCapture = QueuedCapture(
        packageName = "pkg-%03d".format(id),
        appName = "App %03d".format(id),
        eventType = "TYPE_FIXED",
        capturedAtDevice = "2026-09-01T12:00:00+03:00",
        screenText = listOf("text-%03d".format(id)),
        nodes = listOf(
            QueuedCaptureNode(
                text = "node-%03d".format(id),
                contentDescription = null,
                className = "android.widget.TextView",
                viewIdResourceName = null,
                isClickable = false,
                isEditable = false,
            ),
        ),
        isTargetApp = true,
        isSupportedEventType = true,
    )

    private fun newFile(): File {
        val directory = Files.createTempDirectory("persistent-event-queue-test").toFile()
        directory.deleteOnExit()
        return File(directory, "queue.ring").apply { deleteOnExit() }
    }

    private fun capture(id: Int): QueuedCapture = QueuedCapture(
        packageName = "pkg-$id",
        appName = "App $id",
        eventType = "TYPE_$id",
        capturedAtDevice = "2026-09-01T12:00:$id+03:00",
        screenText = listOf("text-$id"),
        nodes = listOf(
            QueuedCaptureNode(
                text = "node-$id",
                contentDescription = if (id % 2 == 0) null else "description-$id",
                className = "android.widget.TextView",
                viewIdResourceName = null,
                isClickable = id % 2 == 0,
                isEditable = false,
            ),
        ),
        isTargetApp = true,
        isSupportedEventType = true,
    )

    private companion object {
        const val DEFAULT_TEST_CAPACITY = 8 * 1024
        const val SMALL_CAPACITY = 512
        const val HEADER_CRC_OFFSET = 72
        const val RECORD_TOTAL_LENGTH_OFFSET = 8
    }
}
