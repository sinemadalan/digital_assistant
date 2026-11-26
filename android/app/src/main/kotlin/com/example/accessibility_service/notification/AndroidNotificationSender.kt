package com.example.accessibility_service.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.accessibility_service.R

interface AppNotificationSender {
    fun canSendNotifications(): Boolean
    fun availability(type: NotificationType): NotificationAvailability =
        if (canSendNotifications()) NotificationAvailability.READY else NotificationAvailability.APP_DISABLED
    fun show(notification: AppNotification): Boolean
    fun cancel(id: Int)
}

class AndroidNotificationSender(
    private val context: Context,
    private val systemNotificationManager: NotificationManager? =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager,
) : AppNotificationSender {
    init { createChannels() }

    override fun canSendNotifications(): Boolean = appAvailability() == NotificationAvailability.READY

    private fun appAvailability(): NotificationAvailability {
        if (systemNotificationManager == null) return NotificationAvailability.UNAVAILABLE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return NotificationAvailability.PERMISSION_REQUIRED
        if (!systemNotificationManager.areNotificationsEnabled()) return NotificationAvailability.APP_DISABLED
        return NotificationAvailability.READY
    }

    override fun availability(type: NotificationType): NotificationAvailability {
        val appState = appAvailability()
        if (appState != NotificationAvailability.READY) return appState
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = systemNotificationManager?.getNotificationChannel(type.channelId)
                ?: return NotificationAvailability.UNAVAILABLE
            if (channel.importance == NotificationManager.IMPORTANCE_NONE) {
                return NotificationAvailability.CHANNEL_DISABLED
            }
        }
        return NotificationAvailability.READY
    }

    override fun show(notification: AppNotification): Boolean {
        if (availability(notification.type) != NotificationAvailability.READY) return false
        return try {
            systemNotificationManager?.notify(notification.id, buildNativeNotification(notification)) ?: return false
            // Do not log server title/body, metadata or authentication material.
            Log.i(TAG, "Notification posted: id=${notification.id}, type=${notification.type}")
            true
        } catch (_: Exception) {
            Log.w(TAG, "Notification delivery failed")
            false
        }
    }

    override fun cancel(id: Int) { systemNotificationManager?.cancel(id) }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channels = NotificationType.entries.map { type ->
            val (name, desc) = when (type) {
                NotificationType.CLASSIFICATION_ALERT ->
                    "İçerik bildirimleri" to "Tespit edilen içerik kategorileri"
                NotificationType.SERVER_ALERT ->
                    context.getString(R.string.notification_channel_alerts_name) to
                        context.getString(R.string.notification_channel_alerts_desc)
                NotificationType.PUSH_NOTIFICATION -> "Push Notifications" to "Updates and push alerts"
                NotificationType.SYSTEM_STATUS -> "System Status" to "Accessibility service status"
            }
            val importance = when (type.importance) {
                NotificationImportance.HIGH -> NotificationManager.IMPORTANCE_HIGH
                NotificationImportance.DEFAULT -> NotificationManager.IMPORTANCE_DEFAULT
                NotificationImportance.LOW -> NotificationManager.IMPORTANCE_LOW
            }
            NotificationChannel(type.channelId, name, importance).apply { description = desc }
        }
        systemNotificationManager?.createNotificationChannels(channels)
    }

    @Suppress("DEPRECATION")
    private fun buildNativeNotification(notification: AppNotification): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, notification.type.channelId)
        } else Notification.Builder(context)

        return builder.setContentTitle(notification.title)
            .setContentText(notification.message)
            .setStyle(Notification.BigTextStyle().bigText(notification.message))
            .setSmallIcon(R.drawable.ic_notification)
            .setAutoCancel(true)
            .setOnlyAlertOnce(notification.onlyAlertOnce)
            .setWhen(notification.timestampMs)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .build()
    }

    companion object { private const val TAG = "NotificationSender" }
}
