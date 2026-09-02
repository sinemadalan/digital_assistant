package com.example.accessibility_service.upload

import com.example.accessibility_service.persistence.QueuedCapture

internal enum class CaptureSubmissionResult {
    SUBMITTED,
    BUFFERED,
    BUFFERED_AFTER_DROPPING_OLDEST,
    UNAVAILABLE,
}

internal data class CaptureBufferAttachResult(
    val submittedCount: Int,
    val rejectedCount: Int,
)

internal fun interface CaptureSink {
    fun enqueue(capture: QueuedCapture, afterPersistence: () -> Unit): Boolean
}

/** Bounded FIFO used only while the persistent capture pipeline is opening. */
internal class CaptureInitializationBuffer(
    private val capacity: Int = DEFAULT_CAPACITY,
) {
    private val lock = Any()
    private val pending = ArrayDeque<PendingCapture>()
    private var sink: CaptureSink? = null
    private var terminal = false

    init {
        require(capacity > 0) { "Initialization buffer capacity must be positive." }
    }

    fun submit(capture: QueuedCapture, afterPersistence: () -> Unit): CaptureSubmissionResult =
        synchronized(lock) {
            if (terminal) return@synchronized CaptureSubmissionResult.UNAVAILABLE
            sink?.let { readySink ->
                return@synchronized if (readySink.enqueue(capture, afterPersistence)) {
                    CaptureSubmissionResult.SUBMITTED
                } else {
                    CaptureSubmissionResult.UNAVAILABLE
                }
            }

            val droppedOldest = pending.size == capacity
            if (droppedOldest) pending.removeFirst()
            pending.addLast(PendingCapture(capture, afterPersistence))
            if (droppedOldest) {
                CaptureSubmissionResult.BUFFERED_AFTER_DROPPING_OLDEST
            } else {
                CaptureSubmissionResult.BUFFERED
            }
        }

    fun attach(readySink: CaptureSink): CaptureBufferAttachResult = synchronized(lock) {
        if (terminal || sink != null) return@synchronized CaptureBufferAttachResult(0, pending.size)
        sink = readySink
        var submitted = 0
        var rejected = 0
        while (pending.isNotEmpty()) {
            val capture = pending.removeFirst()
            if (readySink.enqueue(capture.capture, capture.afterPersistence)) submitted += 1 else rejected += 1
        }
        CaptureBufferAttachResult(submitted, rejected)
    }

    /** Permanently rejects new submissions and returns how many volatile captures were discarded. */
    fun close(): Int = synchronized(lock) {
        terminal = true
        sink = null
        val discarded = pending.size
        pending.clear()
        discarded
    }

    internal fun bufferedCount(): Int = synchronized(lock) { pending.size }

    private data class PendingCapture(
        val capture: QueuedCapture,
        val afterPersistence: () -> Unit,
    )

    companion object {
        const val DEFAULT_CAPACITY = 32
    }
}
