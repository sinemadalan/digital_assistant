package com.example.accessibility_service.notification

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.example.accessibility_service.NativeTokenStore
import com.example.accessibility_service.networking.NotificationsApiClient
import com.example.accessibility_service.networking.NotificationsApiResult
import com.example.accessibility_service.networking.ServerNotification
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Android adapters. Constructed once by the accessibility service, never by the Flutter activity. */
internal fun notificationPollingController(
    context: Context,
    scope: CoroutineScope,
): NotificationPollingController {
    val appContext = context.applicationContext
    val tokens = NativeTokenStore(appContext)
    val api = NotificationsApiClient()
    val manager = AppNotificationManager.getInstance(appContext)
    val identity = DeviceTokenIdentity()
    return NotificationPollingController(
        scope = scope,
        tokens = tokens.tokenChanges,
        createSession = { token ->
            val deviceId = identity.deviceId(token) ?: throw InvalidDeviceTokenException()
            val store = PersistentNotificationStore.open(appContext, deviceId)
            val isCurrent: suspend () -> Boolean = { tokens.getToken() == token }
            val delivery = NotificationDeliveryCoordinator(
                store, manager, api, sessionIsCurrent = isCurrent,
            )
            object : NotificationPollSession {
                override suspend fun cycle(): NotificationPollResult = notificationInboxCycleMutex.withLock {
                    NotificationInboxCycle(
                        canFetch = { store.canFetchPage() },
                        cleanup = { store.cleanup() },
                        resume = { delivery.resumePending(token) },
                        fetch = { api.fetchPending(token) },
                        deliver = { delivery.processFetched(token, it) },
                        canPresent = { manager.availability(NotificationType.CLASSIFICATION_ALERT) == NotificationAvailability.READY },
                        isCurrent = isCurrent,
                        reportDelivery = { outcome ->
                            if (outcome.displayed + outcome.acknowledged + outcome.notFound > 0) {
                                Log.i("Notifications", "Notifications: displayed=${outcome.displayed} acked=${outcome.acknowledged} notFound=${outcome.notFound}")
                            }
                        },
                    ).run()
                }
            }
        },
        invalidateToken = { tokens.clearTokenIfMatches(it) },
        nowMillis = SystemClock::elapsedRealtime,
        report = { Log.i("Notifications", "Notifications: state=${it.name}") },
    )
}

// Also protects a brief service-instance replacement while the old blocking HTTP request exits.
private val notificationInboxCycleMutex = Mutex()

/** Testable one-cycle orchestration: recover local work, check capacity before EACH GET, drain full pages. */
internal class NotificationInboxCycle(
    private val canFetch: suspend () -> Boolean,
    private val cleanup: suspend () -> Unit,
    private val resume: suspend () -> NotificationDeliveryResult,
    private val fetch: suspend () -> NotificationsApiResult<List<ServerNotification>>,
    private val deliver: suspend (List<ServerNotification>) -> NotificationDeliveryResult,
    private val canPresent: () -> Boolean,
    private val isCurrent: suspend () -> Boolean,
    private val reportDelivery: (NotificationDeliveryResult) -> Unit = {},
) {
    suspend fun run(): NotificationPollResult {
        if (!isCurrent()) return NotificationPollResult(NotificationPollStatus.SESSION_CHANGED)
        cleanup()
        val recovered = resume()
        reportDelivery(recovered)
        deliveryFailure(recovered)?.let { return it }
        while (true) {
            currentCoroutineContext().ensureActive()
            if (!isCurrent()) return NotificationPollResult(NotificationPollStatus.SESSION_CHANGED)
            if (!canPresent()) return NotificationPollResult(NotificationPollStatus.DISPLAY_BLOCKED)
            if (!canFetch()) return NotificationPollResult(NotificationPollStatus.NO_CAPACITY)
            when (val response = fetch()) {
                is NotificationsApiResult.Success -> {
                    // Persist even if auth changed during GET; delivery guards prevent stale OS/ack side effects.
                    val delivered = deliver(response.value)
                    reportDelivery(delivered)
                    deliveryFailure(delivered)?.let { return it }
                    if (response.value.size < NotificationsApiClient.MAX_PAGE_SIZE) {
                        return NotificationPollResult(NotificationPollStatus.COMPLETE)
                    }
                }
                NotificationsApiResult.Unauthorized -> return NotificationPollResult(NotificationPollStatus.UNAUTHORIZED)
                is NotificationsApiResult.RetryableHttpError ->
                    return NotificationPollResult(NotificationPollStatus.RETRY, response.retryAfterMillis)
                NotificationsApiResult.Timeout, NotificationsApiResult.NetworkError ->
                    return NotificationPollResult(NotificationPollStatus.RETRY)
                else -> return NotificationPollResult(NotificationPollStatus.CLIENT_ERROR)
            }
        }
    }

    private fun deliveryFailure(result: NotificationDeliveryResult): NotificationPollResult? =
        when (result.status) {
            NotificationDeliveryStatus.COMPLETED -> null
            NotificationDeliveryStatus.DISPLAY_BLOCKED -> NotificationPollResult(NotificationPollStatus.DISPLAY_BLOCKED)
            NotificationDeliveryStatus.RETRYABLE_FAILURE ->
                NotificationPollResult(NotificationPollStatus.RETRY, result.retryAfterMillis)
            NotificationDeliveryStatus.UNAUTHORIZED -> NotificationPollResult(NotificationPollStatus.UNAUTHORIZED)
            NotificationDeliveryStatus.OWNER_CHANGED, NotificationDeliveryStatus.NO_VALID_TOKEN,
            NotificationDeliveryStatus.SESSION_CHANGED -> NotificationPollResult(NotificationPollStatus.SESSION_CHANGED)
            NotificationDeliveryStatus.STORAGE_ERROR -> NotificationPollResult(NotificationPollStatus.STORAGE_ERROR)
            NotificationDeliveryStatus.CLIENT_ERROR -> NotificationPollResult(NotificationPollStatus.CLIENT_ERROR)
        }
}
