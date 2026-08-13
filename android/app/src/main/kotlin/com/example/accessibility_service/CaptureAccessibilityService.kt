package com.example.accessibility_service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.annotation.RequiresApi
import com.example.accessibility_service.Util.ScreenSummary
import com.example.accessibility_service.Util.CaptureNode
import com.example.accessibility_service.Util.NetworkSyncManager
import com.example.accessibility_service.Util.NodeWalker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

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
    companion object {
        private val network_man = NetworkSyncManager("http://192.168.1.57:8000")
        private val nodewalker = NodeWalker();
        private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        private const val TAG = "CaptureA11yService"
        private const val THROTTLE_MS = 2_000L
        private val lastCaptureTimeByPackage = mutableMapOf<String, Long>()
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
        super.onDestroy()
    }

    @RequiresApi(Build.VERSION_CODES.R)
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        Log.d(TAG, "Event")
        if (event == null) return

        val packageName = event.packageName?.toString() ?: return
        val appName = TARGET_APPS[packageName] ?: return
        val now = System.currentTimeMillis()
        val lastCaptureTime = lastCaptureTimeByPackage[packageName] ?: 0L

        if (now - lastCaptureTime < THROTTLE_MS) return

        lastCaptureTimeByPackage[packageName] = now

        val screenSummary = collectScreenSummary()
        Log.d(
            TAG,
            "Captured screen summary: app=$appName, event=${eventName(event.eventType)}, " +
                "package=$packageName, nodeCount=${screenSummary.nodeCount}, " +
                "textCount=${screenSummary.texts.size}, texts=${screenSummary.texts}"
        )
        network_man.sendCaptureSummary(
            packageName = packageName,
            appName = appName,
            eventType = eventName(event.eventType),
            screenSummary = screenSummary,
        )

        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object: TakeScreenshotCallback {
            override fun onFailure(p0: Int) {
                Log.e(TAG, "Error taking screenshot $p0")
            }

            override fun onSuccess(screenshotResult: ScreenshotResult) {
                Log.d(TAG, "Success taking screenshot")
                serviceScope.launch {
                    // 1. Get the Bitmap from your ScreenshotResult (method depends on your specific library)
                    Log.d(TAG, "Processing image")
                    val bitmap: android.graphics.Bitmap = Bitmap.wrapHardwareBuffer(screenshotResult.hardwareBuffer, screenshotResult.colorSpace)!!
                    // 2. Compress to a ByteArrayOutputStream
                    val bos: java.io.ByteArrayOutputStream = java.io.ByteArrayOutputStream()

                    // PNG is lossless, JPEG is smaller but lossy.
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 100, bos)
                    val imageBytes: kotlin.ByteArray = bos.toByteArray()

                    network_man.sendScreenshot(imageBytes, "user_0");
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
