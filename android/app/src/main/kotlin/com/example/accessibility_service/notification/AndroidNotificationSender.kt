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

/**
 * Interface representing notification dispatching mechanism.
 * Allows decoupling from Android framework for clean unit testing and mockability.
 */
interface AppNotificationSender {
    fun canSendNotifications(): Boolean
    fun show(notification: AppNotification): Boolean
    fun cancel(id: Int)
}

/**
 * Concrete Android implementation for managing notification channels and posting notifications.
 */
class AndroidNotificationSender(
    private val context: Context,
    private val systemNotificationManager: NotificationManager? =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager,
) : AppNotificationSender {

    init {
        createChannels()
    }

    /**
     * Checks all preconditions required to post notifications:
     * 1. NotificationManager service must be available.
     * 2. Notifications must not be globally disabled for the app.
     * 3. On Android 13+ (API 33+), POST_NOTIFICATIONS runtime permission must be granted.
     */
    override fun canSendNotifications(): Boolean {
        if (systemNotificationManager == null) return false

        // Check if app-level notifications are enabled
        if (!systemNotificationManager.areNotificationsEnabled()) {
            return false
        }

        // Android 13 (TIRAMISU, API 33) runtime permission check
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val permissionStatus = ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.POST_NOTIFICATIONS,
            )
            if (permissionStatus != PackageManager.PERMISSION_GRANTED) {
                return false
            }
        }

        return true
    }

    override fun show(notification: AppNotification): Boolean {
        if (!canSendNotifications()) {
            Log.w(TAG, "Cannot send notification ${notification.id}: preconditions not met (disabled or missing permission)")
            return false
        }

        val manager = systemNotificationManager ?: return false

        return try {
            val nativeNotification = buildNativeNotification(notification)
            manager.notify(notification.id, nativeNotification)
            Log.i(TAG, "Notification posted: id=${notification.id}, type=${notification.type}, title=${notification.title}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to post notification ${notification.id}: ${e.message}", e)
            false
        }
    }

    override fun cancel(id: Int) {
        systemNotificationManager?.cancel(id)
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && systemNotificationManager != null) {
            val channels = NotificationType.entries.map { type ->
                val (name, desc) = getChannelMetadata(type)
                val importance = when (type.importance) {
                    NotificationImportance.HIGH -> NotificationManager.IMPORTANCE_HIGH
                    NotificationImportance.DEFAULT -> NotificationManager.IMPORTANCE_DEFAULT
                    NotificationImportance.LOW -> NotificationManager.IMPORTANCE_LOW
                }
                NotificationChannel(type.channelId, name, importance).apply {
                    description = desc
                }
            }
            systemNotificationManager.createNotificationChannels(channels)
        }
    }

    private fun getChannelMetadata(type: NotificationType): Pair<String, String> {
        return when (type) {
            NotificationType.SERVER_ALERT -> {
                val name = try {
                    context.getString(R.string.notification_channel_alerts_name)
                } catch (_: Exception) {
                    "Server Detection Alerts"
                }
                val desc = try {
                    context.getString(R.string.notification_channel_alerts_desc)
                } catch (_: Exception) {
                    "Notifications for server content classification detections"
                }
                name to desc
            }
            NotificationType.PUSH_NOTIFICATION -> {
                "Push Notifications" to "Updates and push alerts"
            }
            NotificationType.SYSTEM_STATUS -> {
                "System Status" to "Accessibility service status"
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun buildNativeNotification(appNotification: AppNotification): Notification {
        val channelId = appNotification.type.channelId
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, channelId)
        } else {
            Notification.Builder(context)
        }

        builder.setContentTitle(appNotification.title)
            .setContentText(appNotification.message)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setAutoCancel(true)
            .setWhen(appNotification.timestampMs)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            builder.setVisibility(Notification.VISIBILITY_PUBLIC)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            builder.setStyle(Notification.BigTextStyle().bigText(appNotification.message))
        }

        return builder.build()
    }

    companion object {
        private const val TAG = "NotificationSender"
    }
}
