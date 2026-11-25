package com.example.accessibility_service.networking

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.SequenceInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

class NotificationStreamTransportTest {
    @Test fun usesHttpsBearerSseHeadersAndStreamTimeout() = runBlocking {
        val connection = StreamConnection(200, ByteArrayInputStream("event: ready\ndata: {}\n\n".toByteArray()))
        var requested: URL? = null
        val opened = UrlConnectionNotificationStreamTransport({ requested = it; connection }).open("device-token")
        assertEquals(NotificationsApiClient.BASE_URL + UrlConnectionNotificationStreamTransport.STREAM_PATH, requested.toString())
        assertEquals("https", requested?.protocol)
        assertEquals("Bearer device-token", connection.getRequestProperty("Authorization"))
        assertEquals("text/event-stream", connection.getRequestProperty("Accept"))
        assertEquals(15_000, connection.connectTimeout)
        assertEquals(50_000, connection.readTimeout)
        assertFalse(connection.instanceFollowRedirects)
        assertTrue(opened is NotificationStreamOpenResult.Connected)
        val events = mutableListOf<SseEvent>()
        (opened as NotificationStreamOpenResult.Connected).session.consume { events.add(it) }
        assertEquals(listOf(SseEvent("ready", "{}")), events)
        assertTrue(connection.disconnected)
    }

    @Test fun invalidTokenNeverConnectsAnd401IsClassified() = runBlocking {
        val connection = StreamConnection(401, ByteArrayInputStream(byteArrayOf()))
        var openedCount = 0
        val transport = UrlConnectionNotificationStreamTransport({ openedCount++; connection })
        assertEquals(
            NotificationStreamOpenResult.Failed(NotificationsApiResult.InvalidRequest(NotificationRequestError.INVALID_TOKEN)),
            transport.open("bad\r\ntoken"),
        )
        assertEquals(0, openedCount)
        assertEquals(NotificationStreamOpenResult.Failed(NotificationsApiResult.Unauthorized), transport.open("device-token"))
        assertTrue(connection.disconnected)
    }

    @Test fun cancellingBlockedReadDisconnectsImmediately() = runBlocking {
        val input = BlockingStream()
        val connection = StreamConnection(200, input)
        val opened = UrlConnectionNotificationStreamTransport({ connection }).open("device-token")
            as NotificationStreamOpenResult.Connected
        val reader = launch { opened.session.consume {} }
        assertTrue(withContext(Dispatchers.IO) { input.entered.await(2, TimeUnit.SECONDS) })
        reader.cancelAndJoin()
        assertTrue(connection.disconnected)
        assertTrue(input.closed)
    }

    @Test fun heartbeatCommentsKeepStreamOpenWithoutDispatching() = runBlocking {
        val blocker = BlockingStream()
        val input = SequenceInputStream(ByteArrayInputStream(": ping\n\n: ping\n\n".toByteArray()), blocker)
        val connection = StreamConnection(200, input)
        val opened = UrlConnectionNotificationStreamTransport({ connection }).open("device-token")
            as NotificationStreamOpenResult.Connected
        val events = mutableListOf<SseEvent>()
        val reader = launch { opened.session.consume { events.add(it) } }
        assertTrue(withContext(Dispatchers.IO) { blocker.entered.await(2, TimeUnit.SECONDS) })
        assertTrue(reader.isActive)
        assertTrue(events.isEmpty())
        reader.cancelAndJoin()
        assertTrue(connection.disconnected)
    }
}

private class BlockingStream : InputStream() {
    val entered = CountDownLatch(1)
    private val released = CountDownLatch(1)
    @Volatile var closed = false
    override fun read(): Int {
        entered.countDown()
        released.await()
        return -1
    }
    override fun close() { closed = true; released.countDown() }
}

private class StreamConnection(
    private val status: Int,
    private val input: InputStream,
) : HttpURLConnection(URL("https://example.invalid")) {
    @Volatile var disconnected = false
    override fun connect() = Unit
    override fun disconnect() { disconnected = true; input.close() }
    override fun usingProxy() = false
    override fun getResponseCode() = status
    override fun getContentType() = "text/event-stream"
    override fun getInputStream() = input
}
