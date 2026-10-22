package com.example.accessibility_service.notification

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NotificationPollingControllerTest {
    @Test fun startupPeriodicStopAndCloseAreIdempotent() = runTest {
        val f = PollFixture(this)
        runCurrent()
        assertEquals(0, f.calls.size)
        f.controller.start()
        runCurrent()
        f.controller.start()
        runCurrent()
        assertEquals(1, f.calls.size)
        advanceTimeBy(29_999); runCurrent()
        assertEquals(1, f.calls.size)
        advanceTimeBy(1); runCurrent()
        assertEquals(2, f.calls.size)
        f.controller.stop(); runCurrent()
        advanceTimeBy(60_000); runCurrent()
        assertEquals(2, f.calls.size)
        f.controller.start(); runCurrent()
        assertEquals(3, f.calls.size)
        f.controller.close(); f.controller.start()
        advanceTimeBy(60_000); runCurrent()
        assertEquals(3, f.calls.size)
    }

    @Test fun uploadAndPermissionTriggersAreImmediateAndConflated() = runTest {
        val f = PollFixture(this)
        f.controller.start(); runCurrent()
        repeat(100) { f.controller.requestPoll() }
        runCurrent()
        assertEquals(2, f.calls.size)
        assertEquals(listOf(0L, 0L), f.calls.map { it.second })
    }

    @Test fun triggersDuringSlowRequestNeverOverlapAndCreateOnlyOneFollowup() = runTest {
        val f = PollFixture(this)
        var active = 0
        var maximum = 0
        f.cycle = {
            active++; maximum = maxOf(maximum, active)
            try { delay(5000); NotificationPollResult(NotificationPollStatus.COMPLETE) }
            finally { active-- }
        }
        f.controller.start(); runCurrent()
        repeat(100) { f.controller.requestPoll() }
        advanceTimeBy(5000); runCurrent()
        assertEquals(2, f.calls.size)
        assertEquals(1, maximum)
        advanceTimeBy(5000); runCurrent()
        assertEquals(2, f.calls.size)
        assertEquals(0, active)
    }

    @Test fun retryAfterCannotBeBypassedByTriggerAndSuccessRestoresNormalInterval() = runTest {
        val f = PollFixture(this)
        f.cycle = {
            if (f.calls.size == 1) NotificationPollResult(NotificationPollStatus.RETRY, 10_000)
            else NotificationPollResult(NotificationPollStatus.COMPLETE)
        }
        f.controller.start(); runCurrent()
        advanceTimeBy(2000)
        repeat(100) { f.controller.requestPoll() }
        runCurrent()
        assertEquals(1, f.calls.size)
        advanceTimeBy(8000); runCurrent()
        assertEquals(2, f.calls.size)
        advanceTimeBy(29_999); runCurrent()
        assertEquals(2, f.calls.size)
        advanceTimeBy(1); runCurrent()
        assertEquals(3, f.calls.size)
    }

    @Test fun retryAfterLongerThanBackoffCapIsRespected() = runTest {
        val f = PollFixture(this)
        f.cycle = { NotificationPollResult(NotificationPollStatus.RETRY, 120_000) }
        f.controller.start(); runCurrent()
        advanceTimeBy(60_000)
        f.controller.requestPoll(); runCurrent()
        assertEquals(1, f.calls.size)
        advanceTimeBy(60_000); runCurrent()
        assertEquals(2, f.calls.size)
    }

    @Test fun retryBackoffIncreasesAndIsCapped() = runTest {
        val f = PollFixture(this)
        f.cycle = { NotificationPollResult(NotificationPollStatus.RETRY) }
        f.controller.start(); runCurrent()
        for (wait in listOf(2000L, 4000L, 8000L, 16000L, 32000L, 60000L, 60000L)) {
            val before = f.calls.size
            advanceTimeBy(wait - 1); runCurrent()
            assertEquals(before, f.calls.size)
            advanceTimeBy(1); runCurrent()
            assertEquals(before + 1, f.calls.size)
        }
    }

    @Test fun tokenChangeCancelsAndJoinsOldSessionBeforeOpeningNewOne() = runTest {
        val f = PollFixture(this)
        val lifecycle = mutableListOf<String>()
        f.cycle = { token ->
            lifecycle.add("start:$token")
            try { awaitCancellation() } finally { lifecycle.add("end:$token") }
        }
        f.controller.start(); runCurrent()
        f.tokens.value = "new-token"; runCurrent()
        assertEquals(listOf("start:token", "end:token", "start:new-token"), lifecycle)
        f.tokens.value = null; runCurrent()
        assertEquals("end:new-token", lifecycle.last())
        advanceTimeBy(60_000); runCurrent()
        assertEquals(2, f.calls.size)
    }

    @Test fun lateOld401DoesNotRevokeFreshToken() = runTest {
        val f = PollFixture(this)
        f.cycle = { token ->
            if (token == "token") {
                f.tokens.value = "fresh"
                NotificationPollResult(NotificationPollStatus.UNAUTHORIZED)
            } else NotificationPollResult(NotificationPollStatus.COMPLETE)
        }
        f.controller.start(); runCurrent()
        assertEquals("fresh", f.tokens.value)
        assertEquals(listOf("token", "fresh"), f.calls.map { it.first })
    }

    @Test fun unauthorizedRevokesOnceAndStopsPollingUntilNewToken() = runTest {
        val f = PollFixture(this)
        f.cycle = { NotificationPollResult(NotificationPollStatus.UNAUTHORIZED) }
        f.controller.start(); runCurrent()
        assertNull(f.tokens.value)
        assertEquals(1, f.revoked)
        repeat(20) { f.controller.requestPoll() }
        advanceTimeBy(120_000); runCurrent()
        assertEquals(1, f.calls.size)
    }

    @Test fun invalidDeviceIdentityRevokesWithoutStartingCycle() = runTest {
        val f = PollFixture(this)
        f.failIdentity = true
        f.controller.start(); runCurrent()
        assertEquals(1, f.revoked)
        assertTrue(f.calls.isEmpty())
    }

    @Test fun factoryStorageErrorRecoversAndUploadStormCannotBypassDelay() = runTest {
        val f = PollFixture(this)
        f.failOpen = true
        f.controller.start(); runCurrent()
        f.failOpen = false
        repeat(20) { f.controller.requestPoll() }
        runCurrent()
        assertTrue(f.calls.isEmpty())
        advanceTimeBy(30_000); runCurrent()
        assertEquals(1, f.calls.size)
    }

    @Test fun capacityPauseAndBlockedPermissionHaveDifferentTriggerBehavior() = runTest {
        val f = PollFixture(this)
        f.cycle = { NotificationPollResult(NotificationPollStatus.NO_CAPACITY) }
        f.controller.start(); runCurrent()
        f.controller.requestPoll(); runCurrent()
        assertEquals(1, f.calls.size)
        advanceTimeBy(30_000); runCurrent()
        assertEquals(2, f.calls.size)
        f.cycle = { NotificationPollResult(NotificationPollStatus.DISPLAY_BLOCKED) }
        advanceTimeBy(30_000); runCurrent()
        f.cycle = { NotificationPollResult(NotificationPollStatus.COMPLETE) }
        f.controller.requestPoll(); runCurrent()
        assertEquals(4, f.calls.size)
    }

    @Test fun clientErrorsDoNotBecomeInfiniteRetryLoop() = runTest {
        val f = PollFixture(this)
        f.cycle = { NotificationPollResult(NotificationPollStatus.CLIENT_ERROR) }
        f.controller.start(); runCurrent()
        repeat(20) { f.controller.requestPoll() }
        advanceTimeBy(300_000); runCurrent()
        assertEquals(1, f.calls.size)
        f.controller.stop(); runCurrent()
        f.controller.start(); runCurrent()
        assertEquals(2, f.calls.size)
    }

    @Test fun authStorageReadFailureRetriesWithoutIssuingNetworkRequests() = runTest {
        var reads = 0
        var calls = 0
        val tokens = flow<String?> {
            reads++
            if (reads == 1) throw IOException()
            emit("token")
            awaitCancellation()
        }
        val controller = NotificationPollingController(
            backgroundScope, tokens,
            createSession = { object : NotificationPollSession {
                override suspend fun cycle(): NotificationPollResult {
                    calls++
                    return NotificationPollResult(NotificationPollStatus.COMPLETE)
                }
            } },
            invalidateToken = {},
            nowMillis = { testScheduler.currentTime },
        )
        controller.start(); runCurrent()
        assertEquals(0, calls)
        advanceTimeBy(60_000); runCurrent()
        assertEquals(1, calls)
        controller.close()
    }

    @Test fun closeCancelsInflightWorkAndAuthChangesCannotRestartIt() = runTest {
        val f = PollFixture(this)
        var cancelled = false
        f.cycle = {
            try { awaitCancellation() } finally { cancelled = true }
        }
        f.controller.start(); runCurrent()
        f.controller.close(); runCurrent()
        assertTrue(cancelled)
        f.tokens.value = "new-token"
        advanceTimeBy(120_000); runCurrent()
        assertEquals(1, f.calls.size)
    }

    @Test fun defaultJitteredBackoffRemainsWithinBounds() {
        for (attempt in 0..10) repeat(10) {
            val base = minOf(2000L shl attempt.coerceAtMost(5), 60_000)
            assertTrue(notificationRetryDelay(attempt) in base..minOf(base + base / 4, 60_000))
        }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
private class PollFixture(scope: TestScope) {
    val tokens = MutableStateFlow<String?>("token")
    val calls = mutableListOf<Pair<String, Long>>()
    var cycle: suspend (String) -> NotificationPollResult = { NotificationPollResult(NotificationPollStatus.COMPLETE) }
    var failOpen = false
    var failIdentity = false
    var revoked = 0
    val controller = NotificationPollingController(
        scope.backgroundScope, tokens,
        createSession = { token ->
            if (failOpen) throw IOException()
            if (failIdentity) throw InvalidDeviceTokenException()
            object : NotificationPollSession {
                override suspend fun cycle(): NotificationPollResult {
                    calls.add(token to scope.testScheduler.currentTime)
                    return this@PollFixture.cycle(token)
                }
            }
        },
        invalidateToken = { token ->
            if (tokens.value == token) { revoked++; tokens.value = null }
        },
        nowMillis = { scope.testScheduler.currentTime },
        normalDelayMillis = { 30_000 },
        retryDelayMillis = { minOf(2000L shl it, 60_000) },
    )
}
