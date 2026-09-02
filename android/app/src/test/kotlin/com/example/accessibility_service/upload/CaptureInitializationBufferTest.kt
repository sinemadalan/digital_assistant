package com.example.accessibility_service.upload

import com.example.accessibility_service.persistence.QueuedCapture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureInitializationBufferTest {
    @Test
    fun earlyCapturesDrainBeforePostInitializationCaptureInFifoOrder() {
        val buffer = CaptureInitializationBuffer(capacity = 4)
        val sink = RecordingCaptureSink()

        assertEquals(CaptureSubmissionResult.BUFFERED, buffer.submit(capture("A")) {})
        assertEquals(CaptureSubmissionResult.BUFFERED, buffer.submit(capture("B")) {})
        assertTrue(sink.names.isEmpty())

        assertEquals(CaptureBufferAttachResult(2, 0), buffer.attach(sink))
        assertEquals(CaptureSubmissionResult.SUBMITTED, buffer.submit(capture("C")) {})

        assertEquals(listOf("A", "B", "C"), sink.names)
        assertEquals(0, buffer.bufferedCount())
    }

    @Test
    fun initializationBufferIsBoundedAndDropsOldest() {
        val buffer = CaptureInitializationBuffer(capacity = 2)
        val sink = RecordingCaptureSink()

        buffer.submit(capture("A")) {}
        buffer.submit(capture("B")) {}
        assertEquals(
            CaptureSubmissionResult.BUFFERED_AFTER_DROPPING_OLDEST,
            buffer.submit(capture("C")) {},
        )

        assertEquals(2, buffer.bufferedCount())
        buffer.attach(sink)
        assertEquals(listOf("B", "C"), sink.names)
    }

    @Test
    fun initializationFailureOrDestroyDiscardsVolatileCapturesAndRejectsFutureOnes() {
        val buffer = CaptureInitializationBuffer(capacity = 2)
        buffer.submit(capture("A")) {}
        buffer.submit(capture("B")) {}

        assertEquals(2, buffer.close())
        assertEquals(0, buffer.bufferedCount())
        assertEquals(CaptureSubmissionResult.UNAVAILABLE, buffer.submit(capture("C")) {})
        assertEquals(CaptureBufferAttachResult(0, 0), buffer.attach(RecordingCaptureSink()))
    }

    @Test
    fun initializedStateSubmitsDirectlyWithoutMemoryBuffering() {
        val buffer = CaptureInitializationBuffer(capacity = 2)
        val sink = RecordingCaptureSink()
        buffer.attach(sink)

        assertEquals(CaptureSubmissionResult.SUBMITTED, buffer.submit(capture("A")) {})

        assertEquals(listOf("A"), sink.names)
        assertEquals(0, buffer.bufferedCount())
    }

    @Test
    fun legacyCallbackIsDeferredAlongWithCapture() {
        val buffer = CaptureInitializationBuffer(capacity = 2)
        var legacyCalled = false
        buffer.submit(capture("A")) { legacyCalled = true }
        assertFalse(legacyCalled)

        buffer.attach(RecordingCaptureSink(invokeCallbacks = true))

        assertTrue(legacyCalled)
    }

    private fun capture(name: String) = QueuedCapture(
        packageName = "com.example.$name",
        appName = name,
        eventType = "TYPE_WINDOW_CONTENT_CHANGED",
        capturedAtDevice = "2026-09-02T10:15:30.123+03:00",
        screenText = listOf(name),
        nodes = emptyList(),
        isTargetApp = true,
        isSupportedEventType = true,
    )
}

private class RecordingCaptureSink(
    private val invokeCallbacks: Boolean = false,
) : CaptureSink {
    val names = mutableListOf<String>()

    override fun enqueue(capture: QueuedCapture, afterPersistence: () -> Unit): Boolean {
        names += capture.appName
        if (invokeCallbacks) afterPersistence()
        return true
    }
}
