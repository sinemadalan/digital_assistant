package com.example.accessibility_service.persistence

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

class CaptureCodecException(message: String, cause: Throwable? = null) : Exception(message, cause)

object CaptureBinaryCodec {
    const val FORMAT_VERSION = 1
    const val MAX_PAYLOAD_BYTES = 1024 * 1024
    const val MAX_STRING_BYTES = 16 * 1024
    const val MAX_SCREEN_TEXT_COUNT = 256
    const val MAX_NODE_COUNT = 512

    fun encode(capture: QueuedCapture): ByteArray {
        require(capture.screenText.size <= MAX_SCREEN_TEXT_COUNT) { "Too many screen text entries." }
        require(capture.nodes.size <= MAX_NODE_COUNT) { "Too many capture nodes." }

        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { data ->
            data.writeInt(FORMAT_VERSION)
            data.writeString(capture.packageName)
            data.writeString(capture.appName)
            data.writeString(capture.eventType)
            data.writeString(capture.capturedAtDevice)
            data.writeInt(capture.screenText.size)
            capture.screenText.forEach { text -> data.writeString(text) }
            data.writeInt(capture.nodes.size)
            capture.nodes.forEach { node ->
                data.writeNullableString(node.text)
                data.writeNullableString(node.contentDescription)
                data.writeNullableString(node.className)
                data.writeNullableString(node.viewIdResourceName)
                data.writeBooleanByte(node.isClickable)
                data.writeBooleanByte(node.isEditable)
            }
            data.writeBooleanByte(capture.isTargetApp)
            data.writeBooleanByte(capture.isSupportedEventType)
        }

        return output.toByteArray().also { payload ->
            require(payload.size <= MAX_PAYLOAD_BYTES) { "Encoded capture exceeds the payload limit." }
        }
    }

    @Throws(CaptureCodecException::class)
    fun decode(payload: ByteArray): QueuedCapture {
        if (payload.size > MAX_PAYLOAD_BYTES) {
            throw CaptureCodecException("Payload exceeds the configured limit.")
        }

        try {
            val input = BinaryInput(payload)
            val version = input.readInt()
            if (version != FORMAT_VERSION) {
                throw CaptureCodecException("Unsupported capture payload version: $version")
            }

            val packageName = input.readString()
            val appName = input.readString()
            val eventType = input.readString()
            val capturedAtDevice = input.readString()
            val screenTextCount = input.readCount(MAX_SCREEN_TEXT_COUNT, "screen text")
            val screenText = List(screenTextCount) { input.readString() }
            val nodeCount = input.readCount(MAX_NODE_COUNT, "node")
            val nodes = List(nodeCount) {
                QueuedCaptureNode(
                    text = input.readNullableString(),
                    contentDescription = input.readNullableString(),
                    className = input.readNullableString(),
                    viewIdResourceName = input.readNullableString(),
                    isClickable = input.readBooleanByte(),
                    isEditable = input.readBooleanByte(),
                )
            }
            val capture = QueuedCapture(
                packageName = packageName,
                appName = appName,
                eventType = eventType,
                capturedAtDevice = capturedAtDevice,
                screenText = screenText,
                nodes = nodes,
                isTargetApp = input.readBooleanByte(),
                isSupportedEventType = input.readBooleanByte(),
            )
            if (input.hasRemaining()) {
                throw CaptureCodecException("Capture payload contains trailing data.")
            }
            return capture
        } catch (error: CaptureCodecException) {
            throw error
        } catch (error: Exception) {
            throw CaptureCodecException("Malformed capture payload.", error)
        }
    }

    private fun DataOutputStream.writeString(value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        require(bytes.size <= MAX_STRING_BYTES) { "String exceeds the encoded length limit." }
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataOutputStream.writeNullableString(value: String?) {
        if (value == null) {
            writeByte(NULL_MARKER)
        } else {
            writeByte(VALUE_MARKER)
            writeString(value)
        }
    }

    private fun DataOutputStream.writeBooleanByte(value: Boolean) {
        writeByte(if (value) BOOLEAN_TRUE else BOOLEAN_FALSE)
    }

    private class BinaryInput(payload: ByteArray) {
        private val buffer = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)

        fun readInt(): Int {
            requireRemaining(Int.SIZE_BYTES)
            return buffer.int
        }

        fun readCount(limit: Int, fieldName: String): Int {
            val value = readInt()
            if (value !in 0..limit) {
                throw CaptureCodecException("Invalid $fieldName count: $value")
            }
            return value
        }

        fun readString(): String {
            val length = readInt()
            if (length !in 0..MAX_STRING_BYTES) {
                throw CaptureCodecException("Invalid string length: $length")
            }
            requireRemaining(length)
            val bytes = ByteArray(length)
            buffer.get(bytes)
            return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        }

        fun readNullableString(): String? {
            requireRemaining(Byte.SIZE_BYTES)
            return when (val marker = buffer.get().toInt()) {
                NULL_MARKER -> null
                VALUE_MARKER -> readString()
                else -> throw CaptureCodecException("Invalid nullable string marker: $marker")
            }
        }

        fun readBooleanByte(): Boolean {
            requireRemaining(Byte.SIZE_BYTES)
            return when (val value = buffer.get().toInt()) {
                BOOLEAN_FALSE -> false
                BOOLEAN_TRUE -> true
                else -> throw CaptureCodecException("Invalid boolean value: $value")
            }
        }

        fun hasRemaining(): Boolean = buffer.hasRemaining()

        private fun requireRemaining(byteCount: Int) {
            if (byteCount < 0 || buffer.remaining() < byteCount) {
                throw CaptureCodecException("Capture payload is truncated.")
            }
        }
    }

    private const val NULL_MARKER = 0
    private const val VALUE_MARKER = 1
    private const val BOOLEAN_FALSE = 0
    private const val BOOLEAN_TRUE = 1
}
