package com.example.accessibility_service.networking

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlConnectionCapturesTransportTest {
    @Test
    fun configuresSecurePostHeadersUtf8TimeoutsAndDisablesRedirects() {
        val connection = RecordingConnection(200, validResponse().toByteArray(StandardCharsets.UTF_8))
        val transport = UrlConnectionCapturesTransport { connection }

        transport.post("secret-token", """{"text":"İstanbul 👋"}""")

        assertEquals("POST", connection.requestMethod)
        assertEquals("application/json; charset=utf-8", connection.getRequestProperty("Content-Type"))
        assertEquals("Bearer secret-token", connection.getRequestProperty("Authorization"))
        assertEquals(CapturesApiClient.CONNECT_TIMEOUT_MS, connection.connectTimeout)
        assertEquals(CapturesApiClient.READ_TIMEOUT_MS, connection.readTimeout)
        assertFalse(connection.instanceFollowRedirects)
        assertEquals("""{"text":"İstanbul 👋"}""", connection.written.toString(StandardCharsets.UTF_8.name()))
        assertTrue(connection.outputClosed)
        assertTrue(connection.inputClosed)
        assertTrue(connection.disconnected)
    }

    @Test
    fun closesErrorStreamAndDisconnectsWithoutRetainingErrorBody() {
        val connection = RecordingConnection(500, "sensitive backend detail".toByteArray())

        val response = UrlConnectionCapturesTransport { connection }.post("token", "{}")

        assertEquals(500, response.statusCode)
        assertEquals("", response.body)
        assertTrue(connection.errorClosed)
        assertTrue(connection.disconnected)
    }

    @Test
    fun rejectsResponseOverTwoHundredFiftySixKibAndStillCleansUp() {
        val bytes = ByteArray(CapturesApiClient.MAX_RESPONSE_BYTES + 1) { 'x'.code.toByte() }
        val connection = RecordingConnection(200, bytes)

        assertThrows(ResponseTooLargeException::class.java) {
            UrlConnectionCapturesTransport { connection }.post("token", "{}")
        }
        assertTrue(connection.inputClosed)
        assertTrue(connection.disconnected)
    }
}

private class RecordingConnection(
    private val code: Int,
    responseBytes: ByteArray,
) : HttpURLConnection(URL("https://example.invalid/v1/captures")) {
    val written = ByteArrayOutputStream()
    var outputClosed = false
    var inputClosed = false
    var errorClosed = false
    var disconnected = false

    private val responseInput = ClosingInputStream(responseBytes) { inputClosed = true }
    private val responseError = ClosingInputStream(responseBytes) { errorClosed = true }

    override fun connect() = Unit

    override fun disconnect() {
        disconnected = true
    }

    override fun usingProxy(): Boolean = false

    override fun getResponseCode(): Int = code

    override fun getContentType(): String = "application/json; charset=utf-8"

    override fun getInputStream(): InputStream = responseInput

    override fun getErrorStream(): InputStream = responseError

    override fun getOutputStream(): OutputStream = object : OutputStream() {
        override fun write(value: Int) {
            written.write(value)
        }

        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            written.write(bytes, offset, length)
        }

        override fun close() {
            outputClosed = true
        }
    }
}

private class ClosingInputStream(
    bytes: ByteArray,
    private val onClose: () -> Unit,
) : ByteArrayInputStream(bytes) {
    override fun close() {
        super.close()
        onClose()
    }
}
