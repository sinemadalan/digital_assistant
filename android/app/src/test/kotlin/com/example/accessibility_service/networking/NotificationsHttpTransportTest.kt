package com.example.accessibility_service.networking

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import org.junit.Assert.*
import org.junit.Test

class NotificationsHttpTransportTest {
    @Test
    fun usesHttpsBearerGetTimeoutsNoRedirectsAndUtf8() {
        val body = """{"notifications":[],"description":"İçerik"}"""
        val connection = NotificationConnection(200, body.toByteArray(Charsets.UTF_8))
        var requested: URL? = null
        val response = UrlConnectionNotificationsTransport {
            requested = it
            connection
        }.execute("GET", NotificationsApiClient.PENDING_PATH, "device-token")
        assertEquals(NotificationsApiClient.BASE_URL + NotificationsApiClient.PENDING_PATH, requested.toString())
        assertEquals("https", requested?.protocol)
        assertEquals("GET", connection.requestMethod)
        assertEquals("Bearer device-token", connection.getRequestProperty("Authorization"))
        assertEquals("application/json", connection.getRequestProperty("Accept"))
        assertEquals(15000, connection.connectTimeout)
        assertEquals(30000, connection.readTimeout)
        assertFalse(connection.instanceFollowRedirects)
        assertFalse(connection.doOutput)
        assertEquals(body, response.body)
        assertTrue(connection.inputClosed)
        assertTrue(connection.disconnected)
    }

    @Test
    fun acknowledgementIsBodylessAndDoesNotRead204Input() {
        val connection = NotificationConnection(204)
        val response = UrlConnectionNotificationsTransport { connection }
            .execute("POST", "/v1/notifications/019a0a45-7000-7000-8000-000000000001/ack", "token")
        assertEquals("POST", connection.requestMethod)
        assertFalse(connection.doOutput)
        assertEquals(204, response.statusCode)
        assertEquals("", response.body)
        assertFalse(connection.inputRequested)
        assertTrue(connection.disconnected)
    }

    @Test
    fun exposesRetryAfterButDiscardsErrorBodyAndClosesStream() {
        val connection = NotificationConnection(429, "sensitive detail".toByteArray(), retryAfter = "45")
        val response = UrlConnectionNotificationsTransport { connection }
            .execute("GET", NotificationsApiClient.PENDING_PATH, "token")
        assertEquals("45", response.retryAfter)
        assertEquals("", response.body)
        assertFalse(connection.inputRequested)
        assertTrue(connection.errorClosed)
        assertTrue(connection.disconnected)
    }

    @Test
    fun limitsResponseSizeAndCleansUpOnFailure() {
        val connection = NotificationConnection(
            200, ByteArray(NotificationsApiClient.MAX_RESPONSE_BYTES + 1) { 'x'.code.toByte() },
        )
        assertThrows(NotificationResponseTooLargeException::class.java) {
            UrlConnectionNotificationsTransport { connection }
                .execute("GET", NotificationsApiClient.PENDING_PATH, "token")
        }
        assertTrue(connection.inputClosed)
        assertTrue(connection.disconnected)
        val broken = NotificationConnection(200, failOnStatus = true)
        assertThrows(IOException::class.java) {
            UrlConnectionNotificationsTransport { broken }
                .execute("GET", NotificationsApiClient.PENDING_PATH, "token")
        }
        assertTrue(broken.disconnected)
    }
}

private class NotificationConnection(
    private val code: Int,
    private val bytes: ByteArray = byteArrayOf(),
    private val retryAfter: String? = null,
    private val failOnStatus: Boolean = false,
) : HttpURLConnection(URL("https://example.invalid")) {
    var disconnected = false
    var inputRequested = false
    var inputClosed = false
    var errorClosed = false
    override fun connect() = Unit
    override fun disconnect() { disconnected = true }
    override fun usingProxy() = false
    override fun getResponseCode(): Int {
        if (failOnStatus) throw IOException("connection failed")
        return code
    }
    override fun getHeaderField(name: String): String? = if (name == "Retry-After") retryAfter else null
    override fun getInputStream(): InputStream {
        inputRequested = true
        check(code != 204) { "204 input must not be accessed" }
        return object : ByteArrayInputStream(bytes) {
            override fun close() { inputClosed = true; super.close() }
        }
    }
    override fun getErrorStream(): InputStream? = if (code >= 400) {
        object : ByteArrayInputStream(bytes) {
            override fun close() { errorClosed = true; super.close() }
        }
    } else null
}
