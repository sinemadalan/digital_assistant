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
                                    nativeTokenStore.saveToken(token)
                                } catch (e: Exception) {
                                    result.error(
                                        "TOKEN_STORAGE_ERROR",
                                        "Unable to persist the authentication token.",
                                        null,
                                    )
                                    return@launch
                                }
                                result.success(null)
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
                    else -> result.notImplemented()
                }
            }
    }

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
