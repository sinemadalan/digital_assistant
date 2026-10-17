package com.example.accessibility_service.notification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppNotificationManagerTest {

    private class FakeNotificationSender(
        var allowSend: Boolean = true,
    ) : AppNotificationSender {
        val postedNotifications = mutableListOf<AppNotification>()
        val cancelledIds = mutableListOf<Int>()

        override fun canSendNotifications(): Boolean = allowSend

        override fun show(notification: AppNotification): Boolean {
            if (!canSendNotifications()) return false
            postedNotifications.add(notification)
            return true
        }

        override fun cancel(id: Int) {
            cancelledIds.add(id)
        }
    }

    @Test
    fun preconditionFailsWhenSenderCannotSend() {
        val sender = FakeNotificationSender(allowSend = false)
        val manager = AppNotificationManager(sender)

        assertFalse(manager.canPostNotifications())
        val posted = manager.notifyServerAlert("Title", "Message")
        assertFalse(posted)
        assertTrue(sender.postedNotifications.isEmpty())
    }

    @Test
    fun notifyServerAlertSetsCorrectTypeAndPayload() {
        val sender = FakeNotificationSender(allowSend = true)
        val manager = AppNotificationManager(sender)

        assertTrue(manager.canPostNotifications())
        val posted = manager.notifyServerAlert(
            title = "Phishing Detected",
            message = "Unusual credential entry detected",
            alertType = "SECURITY_ALERT",
            payload = mapOf("risk" to "critical"),
        )

        assertTrue(posted)
        assertEquals(1, sender.postedNotifications.size)
        val notification = sender.postedNotifications.first()

        assertEquals(NotificationType.SERVER_ALERT, notification.type)
        assertEquals("Phishing Detected", notification.title)
        assertEquals("Unusual credential entry detected", notification.message)
        assertEquals("SECURITY_ALERT", notification.payload["alertType"])
        assertEquals("critical", notification.payload["risk"])
    }

    @Test
    fun notifyPushSetsPushType() {
        val sender = FakeNotificationSender(allowSend = true)
        val manager = AppNotificationManager(sender)

        val posted = manager.notifyPush(
            title = "New Update Available",
            message = "Check out the latest features",
            payload = mapOf("version" to "1.2.0"),
        )

        assertTrue(posted)
        assertEquals(1, sender.postedNotifications.size)
        val notification = sender.postedNotifications.first()

        assertEquals(NotificationType.PUSH_NOTIFICATION, notification.type)
        assertEquals("New Update Available", notification.title)
        assertEquals("Check out the latest features", notification.message)
        assertEquals("1.2.0", notification.payload["version"])
    }

    @Test
    fun notifySystemStatusSetsSystemStatusType() {
        val sender = FakeNotificationSender(allowSend = true)
        val manager = AppNotificationManager(sender)

        val posted = manager.notifySystemStatus(
            title = "Service Active",
            message = "Accessibility service is running",
            id = 42,
        )

        assertTrue(posted)
        assertEquals(1, sender.postedNotifications.size)
        val notification = sender.postedNotifications.first()

        assertEquals(NotificationType.SYSTEM_STATUS, notification.type)
        assertEquals(42, notification.id)
        assertEquals("Service Active", notification.title)
    }

    @Test
    fun generatesUniqueNotificationIds() {
        val sender = FakeNotificationSender(allowSend = true)
        val manager = AppNotificationManager(sender)

        manager.notifyServerAlert("Alert 1", "Msg 1")
        manager.notifyServerAlert("Alert 2", "Msg 2")

        assertEquals(2, sender.postedNotifications.size)
        val id1 = sender.postedNotifications[0].id
        val id2 = sender.postedNotifications[1].id
        assertTrue(id1 != id2)
    }

    @Test
    fun cancelDelegatesToSender() {
        val sender = FakeNotificationSender(allowSend = true)
        val manager = AppNotificationManager(sender)

        manager.cancel(1001)
        assertEquals(listOf(1001), sender.cancelledIds)
    }
}
