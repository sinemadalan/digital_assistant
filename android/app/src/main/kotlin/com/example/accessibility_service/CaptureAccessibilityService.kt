package com.example.accessibility_service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.example.accessibility_service.Util.ScreenSummary
import com.example.accessibility_service.Util.CaptureNode
import com.example.accessibility_service.Util.NetworkSyncManager
import com.example.accessibility_service.Util.NodeWalker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

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
        private val network_man = NetworkSyncManager("http://192.168.1.57:8000/capture")
        private val nodewalker = NodeWalker();

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

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
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
    }

    override fun onInterrupt() {
        Log.i(TAG, "Capture accessibility service interrupted")
    }

    private fun collectScreenSummary(): ScreenSummary {
        val rootNode = rootInActiveWindow ?: return ScreenSummary()
        return nodewalker.walk(rootNode);
    }




}
