package com.example.accessibility_service

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.provider.Settings
import androidx.lifecycle.lifecycleScope
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class MainActivity : FlutterActivity(){
    private val EVENT_CHANNEL = "com.your.package/accessibility_status"
    private val METHOD_CHANNEL = "com.your.package/accessibility_commands"
    private val nativeTokenStore by lazy { NativeTokenStore(applicationContext) }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        // 1. EventChannel: Stream the status from Android to Flutter
        EventChannel(flutterEngine.dartExecutor.binaryMessenger, EVENT_CHANNEL)
            .setStreamHandler(object : EventChannel.StreamHandler {
                private var job: Job? = null

                override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
                    // When Flutter starts listening, observe the StateFlow
                    job = lifecycleScope.launch {
                        AccessibilityState.isServiceRunning.collect { isRunning ->
                            events?.success(isRunning)
                        }
                    }
                }

                override fun onCancel(arguments: Any?) {
                    job?.cancel()
                }
            })

        // 2. MethodChannel: Receive commands from Flutter to Android
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, METHOD_CHANNEL)
            .setMethodCallHandler { call, result ->
                when (call.method) {
                    "setPaused" -> {
                        val pause = call.argument<Boolean>("pause") ?: false
                        AccessibilityState.setPaused(pause)
                        result.success(null)
                    }
                    "openSettings" -> {
                        openAccessibilitySettings()
                        result.success(null)
                    }
                    "setAuthToken" -> {
                        val token = call.argument<String>("token")
                        if (token.isNullOrBlank()) {
                            result.error("INVALID_TOKEN", "Authentication token must not be blank.", null)
                        } else {
                            lifecycleScope.launch {
                                try {
                                    val accepted = nativeTokenStore.synchronizeExistingToken(token)
                                    if (accepted) CaptureAccessibilityService.notifyAuthTokenAvailable()
                                    result.success(accepted)
                                } catch (e: Exception) {
                                    result.error(
                                        "TOKEN_STORAGE_ERROR",
                                        "Unable to persist the authentication token.",
                                        null,
                                    )
                                    return@launch
                                }
                            }
                        }
                    }
                    "installFreshAuthToken" -> {
                        val token = call.argument<String>("token")
                        if (token.isNullOrBlank()) {
                            result.error("INVALID_TOKEN", "Authentication token must not be blank.", null)
                        } else {
                            lifecycleScope.launch {
                                try {
                                    nativeTokenStore.installFreshToken(token)
                                    CaptureAccessibilityService.notifyAuthTokenAvailable()
                                } catch (e: Exception) {
                                    result.error(
                                        "TOKEN_STORAGE_ERROR",
                                        "Unable to persist the fresh authentication token.",
                                        null,
                                    )
                                    return@launch
                                }
                                result.success(null)
                            }
                        }
                    }
                    "isReauthenticationRequired" -> {
                        lifecycleScope.launch {
                            try {
                                result.success(nativeTokenStore.isReauthenticationRequired())
                            } catch (e: Exception) {
                                result.error(
                                    "TOKEN_STORAGE_ERROR",
                                    "Unable to read the native authentication state.",
                                    null,
                                )
                            }
                        }
                    }
                    "clearAuthToken" -> {
                        lifecycleScope.launch {
                            try {
                                nativeTokenStore.clearToken()
                            } catch (e: Exception) {
                                result.error(
                                    "TOKEN_STORAGE_ERROR",
                                    "Unable to clear the authentication token.",
                                    null,
                                )
                                return@launch
                            }
                            result.success(null)
                        }
                    }
                    "testLocalNotification" -> {
                        try {
                            val manager = com.example.accessibility_service.notification.AppNotificationManager.getInstance(applicationContext)
                            val posted = manager.notify(com.example.accessibility_service.notification.AppNotification(
                                id = manager.nextNotificationId(),
                                type = com.example.accessibility_service.notification.NotificationType.CLASSIFICATION_ALERT,
                                title = "Test Notification",
                                message = "Hello notification",
                                onlyAlertOnce = true,
                            ))
                            result.success(posted)
                        } catch (e: Exception) {
                            result.error("NOTIFICATION_ERROR", "Failed to send test notification: ${e.message}", null)
                        }
                    }
                    "notificationAvailability" -> {
                        val manager = com.example.accessibility_service.notification.AppNotificationManager.getInstance(applicationContext)
                        result.success(manager.availability(
                            com.example.accessibility_service.notification.NotificationType.CLASSIFICATION_ALERT,
                        ).wireValue)
                    }
                    "requestNotificationPermission" -> requestNotificationPermission(result)
                    else -> result.notImplemented()
                }
            }
    }


    private var notificationPermissionResult: MethodChannel.Result? = null

    private fun requestNotificationPermission(result: MethodChannel.Result) {
        if (notificationPermissionResult != null) {
            result.error("PERMISSION_IN_PROGRESS", "A permission request is already active.", null)
            return
        }
        val manager = com.example.accessibility_service.notification.AppNotificationManager.getInstance(applicationContext)
        val state = manager.availability(com.example.accessibility_service.notification.NotificationType.CLASSIFICATION_ALERT)
        // Older targets cannot control Android 13's notification permission prompt; settings remain usable.
        if (android.os.Build.VERSION.SDK_INT >= 33 && applicationInfo.targetSdkVersion >= 33 &&
            state == com.example.accessibility_service.notification.NotificationAvailability.PERMISSION_REQUIRED
        ) {
            notificationPermissionResult = result
            try {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), NOTIFICATION_PERMISSION_REQUEST)
            } catch (_: Exception) {
                notificationPermissionResult = null
                result.error("PERMISSION_ERROR", "Unable to request notification permission.", null)
            }
            return
        }
        try {
            val intent = if (android.os.Build.VERSION.SDK_INT >= 26) {
                val channelBlocked = state == com.example.accessibility_service.notification.NotificationAvailability.CHANNEL_DISABLED
                Intent(if (channelBlocked) Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS else Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                    .apply {
                        if (channelBlocked) putExtra(
                            Settings.EXTRA_CHANNEL_ID,
                            com.example.accessibility_service.notification.NotificationType.CLASSIFICATION_ALERT.channelId,
                        )
                    }
            } else {
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:$packageName"))
            }
            startActivity(intent)
            result.success(null)
        } catch (_: Exception) {
            result.error("SETTINGS_ERROR", "Unable to open notification settings.", null)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == NOTIFICATION_PERMISSION_REQUEST) {
            notificationPermissionResult?.success(null)
            notificationPermissionResult = null
        }
    }

    override fun onDestroy() {
        notificationPermissionResult?.error("ACTIVITY_CLOSED", "Permission request was interrupted.", null)
        notificationPermissionResult = null
        super.onDestroy()
    }

    private companion object { const val NOTIFICATION_PERMISSION_REQUEST = 8401 }

    private fun openAccessibilitySettings() {
        try {
            val intent = Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS")
            val componentName = ComponentName(this, CaptureAccessibilityService::class.java).flattenToString()
            intent.putExtra("android.intent.extra.COMPONENT_NAME", componentName)
            startActivity(intent)
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }
}
