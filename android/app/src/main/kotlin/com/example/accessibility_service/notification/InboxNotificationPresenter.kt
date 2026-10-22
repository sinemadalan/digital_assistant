package com.example.accessibility_service.notification

import java.time.OffsetDateTime

/** Only the server's generic title/body are rendered; payload metadata never enters the copy. */
internal class InboxNotificationPresenter(
    private val manager: AppNotificationManager,
) : StoredNotificationPresenter {
    override fun show(notification: StoredNotification): Boolean {
        if (notification.state != NotificationDeliveryState.READY_TO_DISPLAY) return false
        val payload = notification.notification ?: return false
        return manager.notify(AppNotification(
            id = notification.androidNotificationId,
            type = NotificationType.CLASSIFICATION_ALERT,
            title = payload.title,
            message = payload.body,
            timestampMs = OffsetDateTime.parse(payload.createdAt).toInstant().toEpochMilli(),
            onlyAlertOnce = true,
        ))
    }
}
