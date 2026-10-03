package com.example.accessibility_service.persistence

import com.example.accessibility_service.Util.BoundsInScreen
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CaptureBinaryCodecTest {
    @Test
    fun structuralTreeAndRootContextRoundTripWithoutReordering() {
        val capture = sampleCapture(nodes = listOf(
            node().copy(className = "root", visibleToUser = true),
            node().copy(className = "container", parentIndex = 0),
            node().copy(text = "caption", parentIndex = 1,
                boundsInScreen = BoundsInScreen(-100, -500, 1080, 2500)),
            node().copy(text = "caption", parentIndex = 1),
        )).copy(
            rootPackageName = "different.root.package",
            windowBoundsInScreen = BoundsInScreen(Int.MIN_VALUE, -500, Int.MAX_VALUE, 2500),
        )
        assertEquals(capture, CaptureBinaryCodec.decode(CaptureBinaryCodec.encode(capture)))
    }

    @Test
    fun rejectsNegativeAndOutOfRangeParentsOnEncodeAndDecode() {
        val valid = sampleCapture(nodes = listOf(node().copy(parentIndex = 0)))
        for (invalid in listOf(-1, Int.MIN_VALUE, 1, Int.MAX_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) {
                CaptureBinaryCodec.encode(valid.copy(nodes = listOf(node().copy(parentIndex = invalid))))
            }
            val payload = CaptureBinaryCodec.encode(valid)
            // Last four bytes are the two capture booleans and null root/window markers.
            ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN).putInt(payload.size - 8, invalid)
            assertThrows(CaptureCodecException::class.java) { CaptureBinaryCodec.decode(payload) }
        }
    }

    @Test
    fun everyTruncatedStructuralPayloadIsRejected() {
        val capture = sampleCapture(nodes = listOf(node().copy(parentIndex = 0))).copy(
            rootPackageName = "root",
            windowBoundsInScreen = BoundsInScreen(-100, -500, 1080, 2500),
        )
        val payload = CaptureBinaryCodec.encode(capture)
        for (length in payload.indices) {
            assertThrows(CaptureCodecException::class.java) {
                CaptureBinaryCodec.decode(payload.copyOf(length))
            }
        }
    }

    @Test
    fun versionOneIsRejectedWithoutLegacyDecode() {
        val payload = CaptureBinaryCodec.encode(sampleCapture())
        ByteBuffer.wrap(payload).putInt(1)
        assertThrows(CaptureCodecException::class.java) { CaptureBinaryCodec.decode(payload) }
    }

    @Test
    fun roundTripPreservesUnicodeTurkishAndEmoji() {
        val capture = sampleCapture(
            screenText = listOf("İstanbul'da şifre", "Merhaba 👋🌍"),
            nodes = listOf(
                QueuedCaptureNode(
                    visibleToUser = true,
                    boundsInScreen = BoundsInScreen(-100, -500, 1080, 2500),
                    parentIndex = null,
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
        val capture = sampleCapture(nodes = listOf(node()))

        assertEquals(capture, CaptureBinaryCodec.decode(CaptureBinaryCodec.encode(capture)))
    }

    @Test
    fun maxConfiguredNodeAndTextCountsRoundTrip() {
        val capture = sampleCapture(
            screenText = List(CaptureBinaryCodec.MAX_SCREEN_TEXT_COUNT) { "t$it" },
            nodes = List(CaptureBinaryCodec.MAX_NODE_COUNT) { node().copy(text = "n$it") },
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
        nodes: List<QueuedCaptureNode> = listOf(node().copy(text = "node")),
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

    private fun node() = QueuedCaptureNode(
        visibleToUser = false,
        boundsInScreen = BoundsInScreen(0, 0, 0, 0),
        parentIndex = null,
    )
}
