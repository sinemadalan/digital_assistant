package com.example.accessibility_service.notification

import com.example.accessibility_service.networking.NotificationsApiClient
import com.example.accessibility_service.networking.NotificationsApiResult
import com.example.accessibility_service.networking.ServerNotification
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class NotificationDeliveryStatus {
    COMPLETED,
    DISPLAY_BLOCKED,
    NO_VALID_TOKEN,
    UNAUTHORIZED,
    RETRYABLE_FAILURE,
    CLIENT_ERROR,
    STORAGE_ERROR,
    OWNER_CHANGED,
}

data class NotificationDeliveryResult(
    val status: NotificationDeliveryStatus,
    val displayed: Int = 0,
    val acknowledged: Int = 0,
    val notFound: Int = 0,
    val retryAfterMillis: Long? = null,
)

/**
 * Durable save -> stable-ID Android delivery -> receipt acknowledgement.
 * No pending GET, timer or hidden retry. The future single poll coordinator owns fetching,
 * capacity preflight, auth/session lifecycle and retry scheduling.
 */
class NotificationDeliveryCoordinator internal constructor(
    private val store: DeliveryNotificationStore,
    private val presenter: StoredNotificationPresenter,
    private val acknowledger: NotificationAcknowledger,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    constructor(
        store: PersistentNotificationStore,
        manager: AppNotificationManager,
        apiClient: NotificationsApiClient = NotificationsApiClient(),
        nowMillis: () -> Long = System::currentTimeMillis,
    ) : this(
        PersistentDeliveryStore(store),
        InboxNotificationPresenter(manager),
        ApiNotificationAcknowledger(apiClient),
        nowMillis,
    )

    // Wait rather than dropping an already-consumed fetched batch when another call is active.
    private val deliveryMutex = Mutex()

    suspend fun processFetched(
        token: String?,
        notifications: List<ServerNotification>,
    ): NotificationDeliveryResult = deliver(token, notifications)

    suspend fun resumePending(token: String?): NotificationDeliveryResult = deliver(token, null)

    private suspend fun deliver(
        token: String?,
        fetched: List<ServerNotification>?,
    ): NotificationDeliveryResult = deliveryMutex.withLock {
        var displayed = 0
        var acknowledged = 0
        var notFound = 0
        fun result(status: NotificationDeliveryStatus, retryAfter: Long? = null) =
            NotificationDeliveryResult(status, displayed, acknowledged, notFound, retryAfter)

        suspend fun acknowledge(id: String): NotificationDeliveryResult? {
            return when (val response = acknowledger.acknowledge(requireNotNull(token), id)) {
                is NotificationsApiResult.Success -> {
                    store.markAcknowledged(id)
                    acknowledged++
                    null
                }
                NotificationsApiResult.NotFound -> {
                    store.markAcknowledgementNotFound(id)
                    notFound++
                    null
                }
                NotificationsApiResult.Unauthorized -> result(NotificationDeliveryStatus.UNAUTHORIZED)
                is NotificationsApiResult.RetryableHttpError ->
                    result(NotificationDeliveryStatus.RETRYABLE_FAILURE, response.retryAfterMillis)
                NotificationsApiResult.Timeout, NotificationsApiResult.NetworkError ->
                    result(NotificationDeliveryStatus.RETRYABLE_FAILURE)
                else -> result(NotificationDeliveryStatus.CLIENT_ERROR)
            }
        }

        try {
            // Persist every fetched row even if auth disappeared since the GET. No OS side effects yet.
            if (fetched != null) store.saveFetched(fetched)
            if (token.isNullOrBlank()) return@withLock result(NotificationDeliveryStatus.NO_VALID_TOKEN)

            // Retry old receipts before new delivery. These rows must never be re-presented.
            for (row in store.pendingAcknowledgements()) {
                acknowledge(row.id)?.let { return@withLock it }
            }
            for (row in store.pendingDisplay()) {
                if (row.expiresAtMillis <= nowMillis()) continue
                val accepted = try {
                    presenter.show(row)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    false
                }
                if (!accepted) return@withLock result(NotificationDeliveryStatus.DISPLAY_BLOCKED)
                displayed++
                // A failed durable transition aborts before ack. A retry reuses the persisted OS ID.
                if (store.markDisplayed(row.id)) {
                    acknowledge(row.id)?.let { return@withLock it }
                }
            }
            result(NotificationDeliveryStatus.COMPLETED)
        } catch (error: CancellationException) {
            throw error
        } catch (_: NotificationStoreOwnerChangedException) {
            result(NotificationDeliveryStatus.OWNER_CHANGED)
        } catch (_: IOException) {
            result(NotificationDeliveryStatus.STORAGE_ERROR)
        }
    }
}

internal interface DeliveryNotificationStore {
    suspend fun saveFetched(notifications: List<ServerNotification>)
    suspend fun pendingDisplay(): List<StoredNotification>
    suspend fun pendingAcknowledgements(): List<StoredNotification>
    suspend fun markDisplayed(id: String): Boolean
    suspend fun markAcknowledged(id: String): Boolean
    suspend fun markAcknowledgementNotFound(id: String): Boolean
}

internal fun interface StoredNotificationPresenter {
    fun show(notification: StoredNotification): Boolean
}

internal fun interface NotificationAcknowledger {
    suspend fun acknowledge(token: String, id: String): NotificationsApiResult<Unit>
}

private class PersistentDeliveryStore(private val store: PersistentNotificationStore) : DeliveryNotificationStore {
    override suspend fun saveFetched(notifications: List<ServerNotification>) { store.saveFetched(notifications) }
    override suspend fun pendingDisplay() = store.pendingDisplay()
    override suspend fun pendingAcknowledgements() = store.pendingAcknowledgements()
    override suspend fun markDisplayed(id: String) = store.markDisplayed(id)
    override suspend fun markAcknowledged(id: String) = store.markAcknowledged(id)
    override suspend fun markAcknowledgementNotFound(id: String) = store.markAcknowledgementNotFound(id)
}

private class ApiNotificationAcknowledger(private val api: NotificationsApiClient) : NotificationAcknowledger {
    override suspend fun acknowledge(token: String, id: String) = api.acknowledge(token, id)
}
