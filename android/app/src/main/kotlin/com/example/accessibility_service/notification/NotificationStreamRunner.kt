package com.example.accessibility_service.notification

import android.os.SystemClock
import android.util.Log
import com.example.accessibility_service.networking.NotificationStreamOpenResult
import com.example.accessibility_service.networking.NotificationStreamTransport
import com.example.accessibility_service.networking.NotificationsApiResult
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** SSE is only a wake-up source; all fetching, presentation and ack remain in NotificationInboxCycle. */
internal class NotificationStreamRunner(
    private val scope: CoroutineScope,
    private val tokens: Flow<String?>,
    private val transport: NotificationStreamTransport,
    private val polling: NotificationPollingController,
    private val invalidateToken: suspend (String) -> Unit,
    private val nowMillis: () -> Long = SystemClock::elapsedRealtime,
    private val retryDelayMillis: (Int) -> Long = ::notificationRetryDelay,
    private val log: (String) -> Unit = { Log.i("NotificationStream", it) },
) {
    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            tokens.collectLatest { token ->
                if (!token.isNullOrBlank()) runSession(token)
            }
        }
    }

    fun close() {
        job?.cancel()
        job = null
    }

    private suspend fun runSession(token: String) {
        var attempt = 0
        try {
            while (true) {
                log("connect")
                val opened = transport.open(token)
                var error: NotificationsApiResult<Nothing>? = null
                var healthySince: Long? = null
                when (opened) {
                    is NotificationStreamOpenResult.Failed -> error = opened.error
                    is NotificationStreamOpenResult.Connected -> {
                        val session = opened.session
                        try {
                            session.consume { event ->
                                when (event.name) {
                                    "ready" -> {
                                        healthySince = nowMillis()
                                        polling.setStreamConnected(true, token)
                                        polling.requestPoll()
                                        log("ready")
                                    }
                                    "notification" -> {
                                        polling.requestPoll()
                                        log("notification")
                                    }
                                }
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: IOException) {
                            error = NotificationsApiResult.NetworkError
                        } finally {
                            session.close()
                            polling.setStreamConnected(false, token)
                            log("disconnect")
                        }
                    }
                }
                if (error == NotificationsApiResult.Unauthorized) {
                    polling.setStreamConnected(false, token)
                    log("disconnect unauthorized")
                    invalidateToken(token)
                    return
                }
                if (error is NotificationsApiResult.InvalidRequest) return
                if (healthySince?.let { nowMillis() - it >= 60_000 } == true) attempt = 0
                val backoff = retryDelayMillis(attempt.coerceAtMost(5)).coerceIn(1, 60_000)
                attempt = (attempt + 1).coerceAtMost(5)
                val retryAfter = (error as? NotificationsApiResult.RetryableHttpError)?.retryAfterMillis ?: 0
                val delayMillis = maxOf(backoff, retryAfter)
                log("backoff ${delayMillis}ms")
                delay(delayMillis)
            }
        } finally {
            polling.setStreamConnected(false, token)
        }
    }
}
