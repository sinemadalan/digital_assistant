package com.example.accessibility_service.notification

import com.example.accessibility_service.networking.ServerNotification
import java.io.IOException

enum class NotificationDeliveryState {
    READY_TO_DISPLAY,
    AWAITING_ACK,
    ACKNOWLEDGED,
    ACK_NOT_FOUND,
}

data class StoredNotification(
    val id: String,
    val androidNotificationId: Int,
    val expiresAtMillis: Long,
    val state: NotificationDeliveryState,
    /** Removed after ack succeeds or returns 404; the ID tombstone remains until expiry. */
    val notification: ServerNotification?,
)

data class SaveNotificationsResult(
    val inserted: Int,
    val duplicates: Int,
    val expired: Int,
)

data class NotificationStoreStats(
    val totalRecords: Int,
    val awaitingDisplay: Int,
    val awaitingAcknowledgement: Int,
    val completed: Int,
    val serializedBytes: Int,
)

/** Hard limits include completed ID tombstones. Unexpired entries are never silently evicted. */
data class NotificationStoragePolicy(
    val maxRecords: Int = 10_000,
    val maxBytes: Int = 8 * 1024 * 1024,
) {
    init {
        require(maxRecords in 1..10_000)
        require(maxBytes in 1..MAX_NOTIFICATION_STORE_BYTES)
    }
}

class NotificationStoreCapacityException : IOException("Notification storage capacity reached")
class NotificationStoreOwnerChangedException :
    IllegalStateException("Notification storage belongs to a different enrolled device")

internal const val MAX_NOTIFICATION_STORE_BYTES = 8 * 1024 * 1024
internal const val FIRST_INBOX_ANDROID_ID = 1_000_000
internal const val NORMAL_NOTIFICATION_TTL_MILLIS = 7L * 24 * 60 * 60 * 1000
internal const val TEST_NOTIFICATION_TTL_MILLIS = 24L * 60 * 60 * 1000

internal data class NotificationStoreSnapshot(
    val deviceId: String? = null,
    val nextAndroidId: Long = FIRST_INBOX_ANDROID_ID.toLong(),
    val records: List<StoredNotification> = emptyList(),
)
