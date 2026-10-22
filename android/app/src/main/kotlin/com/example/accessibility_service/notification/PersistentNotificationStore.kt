package com.example.accessibility_service.notification

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.dataStore
import com.example.accessibility_service.networking.NotificationsApiClient
import com.example.accessibility_service.networking.ServerNotification
import com.example.accessibility_service.networking.isCanonicalNotificationId
import java.time.OffsetDateTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val Context.notificationInboxDataStore: DataStore<NotificationStoreSnapshot> by dataStore(
    fileName = "device_notification_inbox.json",
    serializer = NotificationStoreSerializer,
)

/**
 * Atomic, durable device inbox. No HTTP or Android notification side effects.
 * All reads and writes verify device ownership, and remove expired records.
 */
class PersistentNotificationStore private constructor(
    private val dataStore: DataStore<NotificationStoreSnapshot>,
    private val deviceId: String,
    private val policy: NotificationStoragePolicy,
    private val nowMillis: () -> Long,
) {
    /**
     * Saves a whole fetched batch atomically, before the caller may display or acknowledge it.
     * Existing IDs never change payload, delivery state, Android ID or expiry.
     * A capacity or disk failure propagates; none of this batch is committed on failure.
     */
    suspend fun saveFetched(notifications: List<ServerNotification>): SaveNotificationsResult {
        require(notifications.size <= NotificationsApiClient.MAX_PAGE_SIZE)
        var result = SaveNotificationsResult(0, 0, 0)
        dataStore.updateInbox { original ->
            val now = nowMillis()
            val current = pruneOwned(original, now)
            val rows = current.records.associateByTo(linkedMapOf()) { it.id }
            var nextId = current.nextAndroidId
            var inserted = 0
            var duplicates = 0
            var expired = 0
            for (notification in notifications) {
                validateNotification(notification)
                if (rows.containsKey(notification.id)) {
                    duplicates++
                    continue
                }
                val ttl = if (notification.isTest) TEST_NOTIFICATION_TTL_MILLIS else NORMAL_NOTIFICATION_TTL_MILLIS
                val createdAtMillis = OffsetDateTime.parse(notification.createdAt).toInstant().toEpochMilli()
                // Also cap by first receipt to avoid indefinite retention on a future server timestamp.
                val expiresAt = minOf(Math.addExact(createdAtMillis, ttl), Math.addExact(now, ttl))
                if (expiresAt <= now) {
                    expired++
                    continue
                }
                if (nextId > Int.MAX_VALUE) throw NotificationStoreCapacityException()
                rows[notification.id] = StoredNotification(
                    id = notification.id,
                    androidNotificationId = nextId.toInt(),
                    expiresAtMillis = expiresAt,
                    state = NotificationDeliveryState.READY_TO_DISPLAY,
                    notification = notification,
                )
                nextId++
                inserted++
            }
            val updated = current.copy(nextAndroidId = nextId, records = rows.values.toList())
            enforceCapacity(updated)
            result = SaveNotificationsResult(inserted, duplicates, expired)
            updated
        }
        return result
    }

    suspend fun pendingDisplay(): List<StoredNotification> =
        cleanedSnapshot().records.filter { it.state == NotificationDeliveryState.READY_TO_DISPLAY }

    /** Persisted outbox for ack retries; reading does not consume or re-display its entries. */
    suspend fun pendingAcknowledgements(): List<StoredNotification> =
        cleanedSnapshot().records.filter { it.state == NotificationDeliveryState.AWAITING_ACK }

    /** Call only after Android accepts the notification. Retries do not regress terminal states. */
    suspend fun markDisplayed(id: String): Boolean = transition(id, NotificationDeliveryState.AWAITING_ACK)

    /** Call on HTTP 204. Clears the generic message but retains an ID tombstone until expiry. */
    suspend fun markAcknowledged(id: String): Boolean = transition(id, NotificationDeliveryState.ACKNOWLEDGED)

    /** Call on HTTP 404 to stop retrying, retaining deduplication until expiry. */
    suspend fun markAcknowledgementNotFound(id: String): Boolean =
        transition(id, NotificationDeliveryState.ACK_NOT_FOUND)

    suspend fun cleanup(): Int {
        var removed = 0
        dataStore.updateInbox { current ->
            val updated = pruneOwned(current, nowMillis())
            removed = current.records.size - updated.records.size
            updated
        }
        return removed
    }

    suspend fun stats(): NotificationStoreStats {
        val rows = cleanedSnapshot()
        return NotificationStoreStats(
            totalRecords = rows.records.size,
            awaitingDisplay = rows.records.count { it.state == NotificationDeliveryState.READY_TO_DISPLAY },
            awaitingAcknowledgement = rows.records.count { it.state == NotificationDeliveryState.AWAITING_ACK },
            completed = rows.records.count { isTerminal(it.state) },
            serializedBytes = serializedSize(rows),
        )
    }

    /**
     * Conservative preflight for the future SINGLE poll coordinator. Reserve one full API page
     * and its bounded response plus local metadata. A disk error can still occur after a fetch.
     */
    suspend fun canFetchPage(): Boolean {
        val snapshot = cleanedSnapshot()
        return policy.maxRecords - snapshot.records.size >= NotificationsApiClient.MAX_PAGE_SIZE &&
            serializedSize(snapshot).toLong() +
            NotificationsApiClient.MAX_RESPONSE_BYTES +
            NotificationsApiClient.MAX_PAGE_SIZE * 1024 <= policy.maxBytes
    }

    private suspend fun transition(id: String, target: NotificationDeliveryState): Boolean {
        require(isCanonicalNotificationId(id))
        var found = false
        dataStore.updateInbox { original ->
            val current = pruneOwned(original, nowMillis())
            val updated = current.copy(records = current.records.map { row ->
                if (row.id != id) return@map row
                found = true
                if (isTerminal(row.state) || row.state == target) return@map row
                if (target != NotificationDeliveryState.AWAITING_ACK) {
                    check(row.state == NotificationDeliveryState.AWAITING_ACK) {
                        "Notification must be displayed before acknowledgement"
                    }
                }
                row.copy(state = target, notification = if (isTerminal(target)) null else row.notification)
            })
            // A status transition cannot bypass a caller-specified byte limit.
            enforceCapacity(updated)
            updated
        }
        return found
    }

    private suspend fun cleanedSnapshot(): NotificationStoreSnapshot =
        dataStore.updateInbox { pruneOwned(it, nowMillis()) }

    private fun pruneOwned(snapshot: NotificationStoreSnapshot, now: Long): NotificationStoreSnapshot {
        if (snapshot.deviceId != deviceId) throw NotificationStoreOwnerChangedException()
        return snapshot.copy(records = snapshot.records.filter { it.expiresAtMillis > now })
    }

    private suspend fun serializedSize(snapshot: NotificationStoreSnapshot): Int =
        withContext(Dispatchers.IO) { NotificationStoreSerializer.encode(snapshot).size }

    private fun enforceCapacity(snapshot: NotificationStoreSnapshot) {
        if (snapshot.records.size > policy.maxRecords ||
            NotificationStoreSerializer.encode(snapshot).size > policy.maxBytes
        ) {
            throw NotificationStoreCapacityException()
        }
    }

    companion object {
        /**
         * Use the UUID from trusted enrollment. It scopes only LOCAL storage, never an HTTP inbox.
         * Switching enrolled devices clears the old local inbox; stale store handles fail closed.
         */
        suspend fun open(
            context: Context,
            deviceId: String,
        ): PersistentNotificationStore = open(context.applicationContext.notificationInboxDataStore, deviceId)

        internal suspend fun open(
            dataStore: DataStore<NotificationStoreSnapshot>,
            deviceId: String,
            policy: NotificationStoragePolicy = NotificationStoragePolicy(),
            nowMillis: () -> Long = System::currentTimeMillis,
        ): PersistentNotificationStore {
            require(isCanonicalNotificationId(deviceId))
            dataStore.updateInbox { snapshot ->
                if (snapshot.deviceId == deviceId) {
                    snapshot.copy(records = snapshot.records.filter { it.expiresAtMillis > nowMillis() })
                } else {
                    NotificationStoreSnapshot(deviceId = deviceId, nextAndroidId = snapshot.nextAndroidId)
                }
            }
            return PersistentNotificationStore(dataStore, deviceId, policy, nowMillis)
        }
    }
}

internal fun isTerminal(state: NotificationDeliveryState): Boolean =
    state == NotificationDeliveryState.ACKNOWLEDGED || state == NotificationDeliveryState.ACK_NOT_FOUND

internal fun validateNotification(notification: ServerNotification) {
    require(isCanonicalNotificationId(notification.id))
    require(notification.category.isNotBlank() && notification.title.isNotBlank() && notification.body.isNotBlank())
    OffsetDateTime.parse(notification.createdAt)
}

/** DataStore transforms inherit caller context; keep snapshot processing off the UI thread. */
private suspend fun DataStore<NotificationStoreSnapshot>.updateInbox(
    transform: (NotificationStoreSnapshot) -> NotificationStoreSnapshot,
): NotificationStoreSnapshot = withContext(Dispatchers.IO) {
    updateData { transform(it) }
}
