package com.example.accessibility_service.notification

import com.example.accessibility_service.networking.NotificationSeverity
import com.example.accessibility_service.networking.ServerNotification
import org.junit.Assert.*
import org.junit.Test

class InboxNotificationPresenterTest {
    @Test fun preservesServerCopyAndStableIdWithoutAppendingEvidenceOrCategory() {
        val sender = RecordingInboxSender()
        val presenter = InboxNotificationPresenter(AppNotificationManager(sender))
        val row = storedRow()
        assertTrue(presenter.show(row))
        assertTrue(presenter.show(row))
        val shown = sender.notifications.first()
        assertEquals(row.androidNotificationId, shown.id)
        assertEquals(shown.id, sender.notifications.last().id)
        assertEquals(row.notification?.title, shown.title)
        assertEquals(row.notification?.body, shown.message)
        assertEquals(NotificationType.CLASSIFICATION_ALERT, shown.type)
        assertEquals(NotificationImportance.DEFAULT, shown.type.importance)
        assertTrue(shown.onlyAlertOnce)
        assertTrue(shown.payload.isEmpty())
        assertEquals(1792659600000L, shown.timestampMs)
    }

    @Test fun terminalOrAlreadyDisplayedRowsAreNeverPresented() {
        val sender = RecordingInboxSender()
        val presenter = InboxNotificationPresenter(AppNotificationManager(sender))
        for (state in listOf(NotificationDeliveryState.AWAITING_ACK,
            NotificationDeliveryState.ACKNOWLEDGED, NotificationDeliveryState.ACK_NOT_FOUND)) {
            assertFalse(presenter.show(storedRow().copy(state = state)))
        }
        assertFalse(presenter.show(storedRow().copy(notification = null)))
        assertTrue(sender.notifications.isEmpty())
    }

    @Test fun channelOrPermissionFailureIsReturnedToCoordinator() {
        val sender = RecordingInboxSender()
        sender.allowed = false
        assertFalse(InboxNotificationPresenter(AppNotificationManager(sender)).show(storedRow()))
        assertTrue(sender.notifications.isEmpty())
    }
}

private class RecordingInboxSender : AppNotificationSender {
    val notifications = mutableListOf<AppNotification>()
    var allowed = true
    override fun canSendNotifications() = allowed
    override fun show(notification: AppNotification): Boolean {
        if (!allowed) return false
        notifications.add(notification)
        return true
    }
    override fun cancel(id: Int) = Unit
}

private fun storedRow() = StoredNotification(
    id = "019a0a45-7000-7000-8000-000000000001",
    androidNotificationId = 1_000_005,
    expiresAtMillis = Long.MAX_VALUE,
    state = NotificationDeliveryState.READY_TO_DISPLAY,
    notification = ServerNotification(
        id = "019a0a45-7000-7000-8000-000000000001",
        category = "kendine_zarar", severity = NotificationSeverity.CRITICAL,
        title = "<b>GiDA</b>", body = "Generic notification.", createdAt = "2026-10-22T09:00:00Z", isTest = true,
    ),
)
