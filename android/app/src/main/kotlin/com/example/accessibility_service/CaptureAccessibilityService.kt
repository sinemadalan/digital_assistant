package com.example.accessibility_service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import androidx.annotation.RequiresApi
import com.example.accessibility_service.Util.NetworkSyncManager
import com.example.accessibility_service.Util.NodeWalker
import com.example.accessibility_service.Util.ScreenSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean

object AccessibilityState {
    private val _isServiceRunning = MutableStateFlow(false)
    val isServiceRunning: StateFlow<Boolean> = _isServiceRunning
    // Tracks if the user hit "Pause" in Flutter UI
    private val _isPaused = MutableStateFlow(false)
    val isPaused: StateFlow<Boolean> = _isPaused
    fun setRunning(isRunning: Boolean) {
        _isServiceRunning.value = isRunning
    }
    fun setPaused(pause: Boolean) {
        _isPaused.value = pause
    }
}
class CaptureAccessibilityService : AccessibilityService() {

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    companion object {
        private val network_man = NetworkSyncManager("http://10.0.2.2:8000")
        private val nodewalker = NodeWalker();
        private const val TAG = "CaptureA11yService"
        private const val SUMMARY_THROTTLE_MS = 5_000L
        private const val SCREENSHOT_THROTTLE_MS = 60_000L
        private const val SCREENSHOT_JPEG_QUALITY = 80
        private val lastSummaryTimeByPackage = mutableMapOf<String, Long>()
        private val lastScreenshotTimeByPackage = mutableMapOf<String, Long>()
        private val screenshotInProgress = AtomicBoolean(false)
        private val TARGET_APPS = mapOf(
            "com.instagram.android" to "Instagram",
            "com.whatsapp" to "WhatsApp",
            "com.facebook.katana" to "Facebook",
        )

        private fun eventName(eventType: Int): String {
            return when (eventType) {
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> "TYPE_WINDOW_STATE_CHANGED"
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> "TYPE_WINDOW_CONTENT_CHANGED"
                else -> eventType.toString()
            }
        }
    }
    override fun onServiceConnected() {
        super.onServiceConnected()
        AccessibilityState.setRunning(true)
        Log.i(TAG, "Capture accessibility service connected")
    }
    override fun onUnbind(intent: Intent?) : Boolean {
        AccessibilityState.setRunning(false)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        // Catch-all for when the service is destroyed
        AccessibilityState.setRunning(false)
        serviceScope.cancel()
        super.onDestroy()
    }

    @RequiresApi(Build.VERSION_CODES.R)
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (AccessibilityState.isPaused.value) return
        if (event == null) return

        val packageName = event.packageName?.toString() ?: return
        val appName = TARGET_APPS[packageName] ?: return
        val now = System.currentTimeMillis()
        val lastSummaryTime = lastSummaryTimeByPackage[packageName] ?: 0L
        val shouldSendSummary = now - lastSummaryTime >= SUMMARY_THROTTLE_MS

        val lastScreenshotTime = lastScreenshotTimeByPackage[packageName] ?: 0L
        val shouldSendScreenshot = now - lastScreenshotTime >= SCREENSHOT_THROTTLE_MS

        if (!shouldSendSummary && !shouldSendScreenshot) return
        Log.d(TAG, "Captured event for $appName: ${eventName(event.eventType)}")

        val screenSummary = collectScreenSummary()
        Log.d(
            TAG,
            "Captured screen summary: app=$appName, event=${eventName(event.eventType)}, " +
                "package=$packageName, nodeCount=${screenSummary.nodeCount}, " +
                "textCount=${screenSummary.texts.size}, texts=${screenSummary.texts}"
        )
        if (shouldSendSummary) {
            lastSummaryTimeByPackage[packageName] = now
            network_man.sendCaptureSummary(
                packageName = packageName,
                appName = appName,
                eventType = eventName(event.eventType),
                screenSummary = screenSummary,
            )
        }

        if (!shouldSendScreenshot || !screenshotInProgress.compareAndSet(false, true)) return
        lastScreenshotTimeByPackage[packageName] = now
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object: TakeScreenshotCallback {
            override fun onFailure(p0: Int) {
                screenshotInProgress.set(false)
                try {
                    //adb shell settings put global hidden_api_policy 1
                    //adb shell settings delete global hidden_api_policy
                    val field = AccessibilityService::class.java.getDeclaredField("ACCESSIBILITY_TAKE_SCREENSHOT_REQUEST_INTERVAL_TIMES_MS")
                    field.setAccessible(true); // Bypass visibility modifiers
                    val intervalMs = field.getInt(null) // It is a static primitive int
                    Log.d("ScreenshotInterval", "The exact system interval is: $intervalMs ms")

                } catch (e: Exception) {
                    Log.e("ScreenshotInterval", "Failed to read interval via reflection", e)
                }
                Log.e(TAG, "Error taking screenshot $p0")
            }

            override fun onSuccess(screenshotResult: ScreenshotResult) {
                Log.d(TAG, "Success taking screenshot")
                serviceScope.launch {
                    var bitmap: Bitmap? = null
                    try {
                        Log.d(TAG, "Processing image")
                        val screenshotBitmap = Bitmap.wrapHardwareBuffer(
                            screenshotResult.hardwareBuffer,
                            screenshotResult.colorSpace,
                        ) ?: throw IllegalStateException("Could not create bitmap from screenshot")
                        bitmap = screenshotBitmap

                        val imageBytes = ByteArrayOutputStream().use { output ->
                            check(screenshotBitmap.compress(Bitmap.CompressFormat.JPEG, SCREENSHOT_JPEG_QUALITY, output)) {
                                "Could not compress screenshot"
                            }
                            output.toByteArray()
                        }
                        Log.d(TAG, "Screenshot compressed: ${imageBytes.size} bytes")
                        network_man.sendScreenshot(imageBytes, "user_0")
                    } catch (error: Exception) {
                        Log.e(TAG, "Screenshot processing failed: ${error.message}", error)
                    } finally {
                        bitmap?.recycle()
                        screenshotResult.hardwareBuffer.close()
                        screenshotInProgress.set(false)
                    }
                }
            }

        })

    }

    override fun onInterrupt() {
        Log.i(TAG, "Capture accessibility service interrupted")
    }

    private fun collectScreenSummary(): ScreenSummary {
        val rootNode = rootInActiveWindow ?: return ScreenSummary()
        return nodewalker.walk(rootNode);
    }




}
