package com.example.accessibility_service.notification

import com.example.accessibility_service.networking.NotificationStreamOpenResult
import com.example.accessibility_service.networking.NotificationStreamSession
import com.example.accessibility_service.networking.NotificationStreamTransport
import com.example.accessibility_service.networking.NotificationsApiResult
import com.example.accessibility_service.networking.SseEvent
import com.example.accessibility_service.networking.SseParser
import java.io.ByteArrayInputStream
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NotificationStreamRunnerTest {
    @Test fun parserHandlesMultilineDataCommentsAndHeartbeats() {
        val parser = SseParser(ByteArrayInputStream(
            ": ping\n\nevent: ready\ndata: {}\n\n: ping\n\nevent: notification\ndata: one\ndata: two\n\n".toByteArray(),
        ))
        assertEquals(SseEvent("ready", "{}"), parser.nextEvent())
        assertEquals(SseEvent("notification", "one\ntwo"), parser.nextEvent())
        assertNull(parser.nextEvent())
    }

    @Test fun oversizedLineFailsClosed() {
        val parser = SseParser(ByteArrayInputStream(("data: " + "x".repeat(8192) + "\n\n").toByteArray()))
        assertThrows(IOException::class.java) { parser.nextEvent() }
    }

    @Test fun readyAndNotificationWakePollerButHeartbeatOnlyDoesNot() = runTest {
        val fixture = StreamFixture(this)
        val session = FakeSession(listOf(SseEvent("ready", "{}"), SseEvent("notification", "{\"id\":\"ignored\"}")))
        fixture.transport.results.add(NotificationStreamOpenResult.Connected(session))
        fixture.start()
        runCurrent()
        assertTrue(session.closed)
        assertEquals(2, fixture.calls)
        fixture.close()
    }

    @Test fun eofReconnectsAfterBackoffAndResetsAfterHealthyMinute() = runTest {
        val fixture = StreamFixture(this)
        fixture.transport.results.add(NotificationStreamOpenResult.Connected(FakeSession(emptyList())))
        fixture.transport.results.add(NotificationStreamOpenResult.Connected(FakeSession(emptyList())))
        fixture.start(); runCurrent()
        assertEquals(1, fixture.transport.opens)
        advanceTimeBy(1999); runCurrent()
        assertEquals(1, fixture.transport.opens)
        advanceTimeBy(1); runCurrent()
        assertEquals(2, fixture.transport.opens)
        fixture.close()
    }

    @Test fun retryAfterOverridesReconnectBackoff() = runTest {
        val fixture = StreamFixture(this)
        fixture.transport.results.add(NotificationStreamOpenResult.Failed(
            NotificationsApiResult.RetryableHttpError(429, 12_000),
        ))
        fixture.transport.results.add(NotificationStreamOpenResult.Connected(FakeSession()))
        fixture.start(); runCurrent()
        advanceTimeBy(11_999); runCurrent()
        assertEquals(1, fixture.transport.opens)
        advanceTimeBy(1); runCurrent()
        assertEquals(2, fixture.transport.opens)
        fixture.close()
    }

    @Test fun minuteOfHealthyConnectionResetsReconnectBackoff() = runTest {
        val fixture = StreamFixture(this)
        fixture.transport.results.add(NotificationStreamOpenResult.Failed(NotificationsApiResult.NetworkError))
        fixture.transport.results.add(NotificationStreamOpenResult.Connected(
            FakeSession(listOf(SseEvent("ready", "{}")), holdMillis = 60_000),
        ))
        fixture.transport.results.add(NotificationStreamOpenResult.Connected(FakeSession()))
        fixture.start(); runCurrent()
        advanceTimeBy(2_000); runCurrent()
        advanceTimeBy(60_000); runCurrent()
        advanceTimeBy(1_999); runCurrent()
        assertEquals(2, fixture.transport.opens)
        advanceTimeBy(1); runCurrent()
        assertEquals(3, fixture.transport.opens)
        fixture.close()
    }

    @Test fun unauthorizedRevokesMatchingTokenAndDoesNotReconnect() = runTest {
        val fixture = StreamFixture(this)
        fixture.transport.results.add(NotificationStreamOpenResult.Failed(NotificationsApiResult.Unauthorized))
        fixture.start(); runCurrent()
        assertNull(fixture.tokens.value)
        advanceTimeBy(120_000); runCurrent()
        assertEquals(1, fixture.transport.opens)
        fixture.close()
    }

    @Test fun cancellationClosesActiveSessionPromptly() = runTest {
        val fixture = StreamFixture(this)
        val session = FakeSession(listOf(SseEvent("ready", "{}")), hold = true)
        fixture.transport.results.add(NotificationStreamOpenResult.Connected(session))
        fixture.start(); runCurrent()
        assertFalse(session.closed)
        fixture.close(); runCurrent()
        assertTrue(session.closed)
    }

    @Test fun oversizedSseLineCausesReconnectWithBackoff() = runTest {
        val fixture = StreamFixture(this)
        val oversized = "data: " + "x".repeat(8192) + "\n\n"
        fixture.transport.results.add(NotificationStreamOpenResult.Connected(FakeSession(raw = oversized)))
        fixture.transport.results.add(NotificationStreamOpenResult.Connected(FakeSession(emptyList())))
        fixture.start(); runCurrent()
        assertEquals(1, fixture.transport.opens)
        advanceTimeBy(2000); runCurrent()
        assertEquals(2, fixture.transport.opens)
        fixture.close()
    }
}

private class FakeSession(
    private val events: List<SseEvent> = emptyList(),
    private val hold: Boolean = false,
    private val raw: String? = null,
    private val holdMillis: Long = 0,
) : NotificationStreamSession {
    var closed = false
    override suspend fun consume(onEvent: (SseEvent) -> Unit) {
        raw?.let {
            val parser = SseParser(ByteArrayInputStream(it.toByteArray()))
            while (true) {
                val event = parser.nextEvent() ?: break
                onEvent(event)
            }
        }
        events.forEach(onEvent)
        if (holdMillis > 0) delay(holdMillis)
        if (hold) awaitCancellation()
    }
    override fun close() { closed = true }
}

private class FakeStreamTransport : NotificationStreamTransport {
    val results = ArrayDeque<NotificationStreamOpenResult>()
    var opens = 0
    override suspend fun open(token: String): NotificationStreamOpenResult {
        opens++
        return results.removeFirst()
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
private class StreamFixture(private val scope: kotlinx.coroutines.test.TestScope) {
    val tokens = MutableStateFlow<String?>("token")
    val transport = FakeStreamTransport()
    var calls = 0
    private val polling = NotificationPollingController(
        scope.backgroundScope, tokens,
        createSession = { object : NotificationPollSession {
            override suspend fun cycle(): NotificationPollResult {
                calls++
                return NotificationPollResult(NotificationPollStatus.COMPLETE)
            }
        } },
        invalidateToken = { if (tokens.value == it) tokens.value = null },
        nowMillis = { scope.testScheduler.currentTime },
        normalDelayMillis = { 30_000 },
    )
    private val runner = NotificationStreamRunner(
        scope.backgroundScope, tokens, transport, polling,
        invalidateToken = { if (tokens.value == it) tokens.value = null },
        nowMillis = { scope.testScheduler.currentTime },
        retryDelayMillis = { 2000L shl it },
        log = {},
    )
    fun start() { polling.start(); runner.start() }
    fun close() { runner.close(); polling.close() }
}
