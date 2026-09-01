package com.example.accessibility_service.persistence

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CaptureBinaryCodecTest {
    @Test
    fun roundTripPreservesUnicodeTurkishAndEmoji() {
        val capture = sampleCapture(
            screenText = listOf("İstanbul'da şifre", "Merhaba 👋🌍"),
            nodes = listOf(
                QueuedCaptureNode(
                    text = "Çalışıyor ✅",
                    contentDescription = null,
                    className = "android.widget.TextView",
                    viewIdResourceName = null,
                    isClickable = true,
                    isEditable = false,
                ),
            ),
        )

        assertEquals(capture, CaptureBinaryCodec.decode(CaptureBinaryCodec.encode(capture)))
    }

    @Test
    fun nullableNodeFieldsRoundTrip() {
        val capture = sampleCapture(nodes = listOf(QueuedCaptureNode()))

        assertEquals(capture, CaptureBinaryCodec.decode(CaptureBinaryCodec.encode(capture)))
    }

    @Test
    fun maxConfiguredNodeAndTextCountsRoundTrip() {
        val capture = sampleCapture(
            screenText = List(CaptureBinaryCodec.MAX_SCREEN_TEXT_COUNT) { "t$it" },
            nodes = List(CaptureBinaryCodec.MAX_NODE_COUNT) { QueuedCaptureNode(text = "n$it") },
        )

        assertEquals(capture, CaptureBinaryCodec.decode(CaptureBinaryCodec.encode(capture)))
    }

    @Test
    fun unsupportedPayloadVersionIsRejected() {
        val payload = CaptureBinaryCodec.encode(sampleCapture()).clone()
        ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN).putInt(99)

        assertThrows(CaptureCodecException::class.java) { CaptureBinaryCodec.decode(payload) }
    }

    @Test
    fun truncatedPayloadIsRejected() {
        val payload = CaptureBinaryCodec.encode(sampleCapture())

        assertThrows(CaptureCodecException::class.java) {
            CaptureBinaryCodec.decode(payload.copyOf(payload.size - 1))
        }
    }

    @Test
    fun invalidStringLengthIsRejectedBeforeAllocation() {
        val payload = CaptureBinaryCodec.encode(sampleCapture()).clone()
        ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
            .putInt(Int.SIZE_BYTES, CaptureBinaryCodec.MAX_STRING_BYTES + 1)

        assertThrows(CaptureCodecException::class.java) { CaptureBinaryCodec.decode(payload) }
    }

    @Test
    fun impossibleNodeCountIsRejected() {
        val payload = CaptureBinaryCodec.encode(sampleCapture()).clone()
        val nodeCountOffset = findNodeCountOffset(payload)
        ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
            .putInt(nodeCountOffset, CaptureBinaryCodec.MAX_NODE_COUNT + 1)

        assertThrows(CaptureCodecException::class.java) { CaptureBinaryCodec.decode(payload) }
    }

    @Test
    fun malformedUtf8IsRejected() {
        val payload = ByteBuffer.allocate(9).order(ByteOrder.BIG_ENDIAN)
            .putInt(CaptureBinaryCodec.FORMAT_VERSION)
            .putInt(1)
            .put(0xC3.toByte())
            .array()

        assertThrows(CaptureCodecException::class.java) { CaptureBinaryCodec.decode(payload) }
    }

    @Test
    fun invalidBooleanMarkerIsRejected() {
        val payload = CaptureBinaryCodec.encode(sampleCapture()).clone()
        payload[payload.lastIndex] = 2

        assertThrows(CaptureCodecException::class.java) { CaptureBinaryCodec.decode(payload) }
    }

    @Test
    fun oversizedStringIsRejectedDuringEncode() {
        val capture = sampleCapture(packageName = "x".repeat(CaptureBinaryCodec.MAX_STRING_BYTES + 1))

        assertThrows(IllegalArgumentException::class.java) { CaptureBinaryCodec.encode(capture) }
    }

    private fun findNodeCountOffset(payload: ByteArray): Int {
        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
        buffer.int
        repeat(4) { skipString(buffer) }
        val textCount = buffer.int
        repeat(textCount) { skipString(buffer) }
        return buffer.position()
    }

    private fun skipString(buffer: ByteBuffer) {
        val length = buffer.int
        buffer.position(buffer.position() + length)
    }

    private fun sampleCapture(
        packageName: String = "com.example.app",
        screenText: List<String> = listOf("hello"),
        nodes: List<QueuedCaptureNode> = listOf(QueuedCaptureNode(text = "node")),
    ): QueuedCapture = QueuedCapture(
        packageName = packageName,
        appName = "Example",
        eventType = "TYPE_WINDOW_CONTENT_CHANGED",
        capturedAtDevice = "2026-09-01T12:00:00+03:00",
        screenText = screenText,
        nodes = nodes,
        isTargetApp = true,
        isSupportedEventType = true,
    )
}
