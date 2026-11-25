package com.example.accessibility_service.notification

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.selects.select
import kotlin.random.Random

internal enum class NotificationPollStatus {
    COMPLETE, RETRY, UNAUTHORIZED, SESSION_CHANGED, NO_CAPACITY, DISPLAY_BLOCKED, STORAGE_ERROR, CLIENT_ERROR,
}

internal data class NotificationPollResult(
    val status: NotificationPollStatus,
    val retryAfterMillis: Long? = null,
)

internal interface NotificationPollSession {
    suspend fun cycle(): NotificationPollResult
}

internal class InvalidDeviceTokenException : IllegalArgumentException("A device enrollment is required")

/** One actor for startup, periodic, upload and permission triggers; auth changes cancel-and-join the old actor. */
internal class NotificationPollingController(
    scope: CoroutineScope,
    tokens: Flow<String?>,
    private val createSession: suspend (String) -> NotificationPollSession,
    private val invalidateToken: suspend (String) -> Unit,
    private val nowMillis: () -> Long,
    private val normalDelayMillis: () -> Long = { Random.nextLong(30_000, 60_001) },
    private val retryDelayMillis: (Int) -> Long = ::notificationRetryDelay,
    private val report: (NotificationPollStatus) -> Unit = {},
) {
    private val active = MutableStateFlow(false)
    private val triggers = Channel<Unit>(Channel.CONFLATED)
    private val scheduleChanges = Channel<Unit>(Channel.CONFLATED)
    private val streamLock = Any()
    private var sessionToken: String? = null
    private var streamConnected = false
    @Volatile private var closed = false
    private val runner: Job = scope.launch {
        val recoverableTokens = tokens.retryWhen { error, _ ->
            if (error is CancellationException) throw error
            report(NotificationPollStatus.STORAGE_ERROR)
            delay(60_000)
            true
        }
        combine(active, recoverableTokens) { enabled, token ->
            token?.takeIf { enabled && it.isNotBlank() }
        }.distinctUntilChanged().collectLatest { token ->
            if (token != null) runSession(token)
        }
    }

    fun start() { if (!closed) active.value = true }
    fun stop() {
        active.value = false
        synchronized(streamLock) { streamConnected = false; sessionToken = null }
        scheduleChanges.trySend(Unit)
    }
    fun requestPoll() { if (!closed && active.value) triggers.trySend(Unit) }
    fun setStreamConnected(connected: Boolean) {
        synchronized(streamLock) {
            if (closed || !active.value || sessionToken == null || streamConnected == connected) return
            streamConnected = connected
        }
        scheduleChanges.trySend(Unit)
    }

    /** Ignore a late disconnect from a stream belonging to an older enrollment. */
    fun setStreamConnected(connected: Boolean, token: String) {
        synchronized(streamLock) {
            if (closed || !active.value || sessionToken != token || streamConnected == connected) return
            streamConnected = connected
        }
        scheduleChanges.trySend(Unit)
    }
    fun close() {
        closed = true
        active.value = false
        runner.cancel()
        triggers.close()
        scheduleChanges.close()
    }

    private suspend fun runSession(token: String) {
        synchronized(streamLock) { sessionToken = token; streamConnected = false }
        var session: NotificationPollSession? = null
        var attempt = 0
        var waitMillis = 0L
        var protectBackoff = false
        try { while (true) {
            awaitWindow(waitMillis, protectBackoff)
            currentCoroutineContext().ensureActive()
            // Requests already queued before this cycle are covered by this cycle.
            triggers.tryReceive()
            val result = try {
                val current = session ?: createSession(token).also { session = it }
                current.cycle()
            } catch (error: CancellationException) {
                throw error
            } catch (_: InvalidDeviceTokenException) {
                invalidateToken(token)
                report(NotificationPollStatus.UNAUTHORIZED)
                return
            } catch (_: NotificationStoreOwnerChangedException) {
                NotificationPollResult(NotificationPollStatus.SESSION_CHANGED)
            } catch (_: IOException) {
                NotificationPollResult(NotificationPollStatus.STORAGE_ERROR)
            }
            report(result.status)
            when (result.status) {
                NotificationPollStatus.UNAUTHORIZED -> {
                    // Compare-and-revoke: a late old 401 must not invalidate a fresh enrollment.
                    invalidateToken(token)
                    return
                }
                NotificationPollStatus.SESSION_CHANGED,
                NotificationPollStatus.CLIENT_ERROR -> return
                NotificationPollStatus.RETRY -> {
                    val backoff = retryDelayMillis(attempt.coerceAtMost(5)).coerceIn(1, 60_000)
                    attempt = (attempt + 1).coerceAtMost(5)
                    waitMillis = maxOf(backoff, result.retryAfterMillis ?: 0)
                    protectBackoff = true
                }
                else -> {
                    attempt = 0
                    waitMillis = if (isStreamConnected()) 15 * 60_000L
                        else normalDelayMillis().coerceIn(30_000, 60_000)
                    // Upload/permission bursts may not bypass a storage failure or capacity pause.
                    protectBackoff = result.status == NotificationPollStatus.STORAGE_ERROR ||
                        result.status == NotificationPollStatus.NO_CAPACITY
                }
            }
        } } finally {
            synchronized(streamLock) {
                if (sessionToken == token) { sessionToken = null; streamConnected = false }
            }
        }
    }

    private fun isStreamConnected(): Boolean = synchronized(streamLock) { streamConnected }

    private suspend fun awaitWindow(waitMillis: Long, ignoreTriggers: Boolean) {
        var start = nowMillis()
        var duration = waitMillis
        while (true) {
            val elapsed = (nowMillis() - start).coerceAtLeast(0)
            val remaining = duration - elapsed
            if (remaining <= 0) return
            when (withTimeoutOrNull(remaining) {
                select<Int> {
                    triggers.onReceiveCatching { 1 }
                    scheduleChanges.onReceiveCatching { 2 }
                }
            } ?: 0) {
                0 -> return
                1 -> if (!ignoreTriggers) return
                2 -> if (!ignoreTriggers && duration == 15 * 60_000L && !isStreamConnected()) {
                    start = nowMillis()
                    duration = normalDelayMillis().coerceIn(30_000, 60_000)
                }
            }
        }
    }
}

internal fun notificationRetryDelay(attempt: Int): Long {
    val base = minOf(2_000L shl attempt.coerceIn(0, 5), 60_000L)
    return (base + Random.nextLong(0, base / 4 + 1)).coerceAtMost(60_000L)
}
