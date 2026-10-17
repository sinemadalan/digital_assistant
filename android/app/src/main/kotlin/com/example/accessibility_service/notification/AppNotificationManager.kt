package com.example.accessibility_service.notification

import android.content.Context
import java.util.concurrent.atomic.AtomicInteger

/**
 * High-level Notification Manager.
 * Orchestrates creating and sending notifications across various types, verifying preconditions,
 * and generating unique notification IDs.
 */
class AppNotificationManager(
    private val sender: AppNotificationSender,
) {
    private val idGenerator = AtomicInteger(1000)

    /**
     * Checks whether notifications can currently be delivered to the user.
     */
    fun canPostNotifications(): Boolean = sender.canSendNotifications()

    /**
     * Sends a Server Detection Alert notification (Phase 1).
     *
     * @param title Title of the alert
     * @param message Detailed alert message
     * @param alertType Optional classification detection type (e.g., "SENSITIVE_CONTENT", "SCAM_ALERT")
     * @param payload Optional metadata key-value pairs
     * @return true if notification was posted, false if preconditions failed or posting failed.
     */
    fun notifyServerAlert(
        title: String,
        message: String,
        alertType: String? = null,
        payload: Map<String, String> = emptyMap(),
    ): Boolean {
        val extraPayload = if (alertType != null) {
            payload + ("alertType" to alertType)
        } else {
            payload
        }
        val notification = AppNotification(
            id = nextNotificationId(),
            type = NotificationType.SERVER_ALERT,
            title = title,
            message = message,
            payload = extraPayload,
        )
        return sender.show(notification)
    }

    /**
     * Sends a push notification (Phase 2).
     */
    fun notifyPush(
        title: String,
        message: String,
        payload: Map<String, String> = emptyMap(),
    ): Boolean {
        val notification = AppNotification(
            id = nextNotificationId(),
            type = NotificationType.PUSH_NOTIFICATION,
            title = title,
            message = message,
            payload = payload,
        )
        return sender.show(notification)
    }

    /**
     * Sends a system status notification.
     */
    fun notifySystemStatus(
        title: String,
        message: String,
        id: Int = 1, // Fixed ID for single status notification
    ): Boolean {
        val notification = AppNotification(
            id = id,
            type = NotificationType.SYSTEM_STATUS,
            title = title,
            message = message,
        )
        return sender.show(notification)
    }

    /**
     * Dispatches any custom [AppNotification].
     */
    fun notify(notification: AppNotification): Boolean {
        return sender.show(notification)
    }

    /**
     * Cancels an existing notification by ID.
     */
    fun cancel(id: Int) {
        sender.cancel(id)
    }

    fun nextNotificationId(): Int = idGenerator.incrementAndGet()

    companion object {
        @Volatile
        private var defaultInstance: AppNotificationManager? = null

        fun getInstance(context: Context): AppNotificationManager {
            return defaultInstance ?: synchronized(this) {
                defaultInstance ?: AppNotificationManager(
                    AndroidNotificationSender(context.applicationContext)
                ).also { defaultInstance = it }
            }
        }
    }
}
