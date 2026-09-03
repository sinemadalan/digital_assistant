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
import com.example.accessibility_service.networking.CapturesApiClient
import com.example.accessibility_service.persistence.PersistentEventQueue
import com.example.accessibility_service.upload.AccessibilityCaptureMapper
import com.example.accessibility_service.upload.CaptureInitializationBuffer
import com.example.accessibility_service.upload.CaptureSubmissionResult
import com.example.accessibility_service.upload.CaptureQueueBridge
import com.example.accessibility_service.upload.UploadCoordinator
import com.example.accessibility_service.upload.PipelineInitializationRetryGate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.time.OffsetDateTime
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
    private val persistentLifecycleLock = Any()
    private val captureInitializationBuffer = CaptureInitializationBuffer()
    private var serviceDestroyed = false
    @Volatile private var persistentQueue: PersistentEventQueue? = null
    @Volatile private var captureQueueBridge: CaptureQueueBridge? = null
    private val persistentInitializationGate by lazy {
        PipelineInitializationRetryGate(
            scope = serviceScope,
            initialize = ::initializePersistentCapturePipeline,
            pipelineLogger = { message -> Log.i(PHASE5A_TAG, message) },
        )
    }
    companion object {
        private val network_man = NetworkSyncManager("http://10.0.2.2:8000")
        private val nodewalker = NodeWalker();
        private const val TAG = "CaptureA11yService"
        private const val PHASE5A_TAG = "Phase5A"
        private const val SUMMARY_THROTTLE_MS = 5_000L
        private const val SCREENSHOT_THROTTLE_MS = 60_000L
        private const val SCREENSHOT_JPEG_QUALITY = 80
        private val lastSummaryTimeByPackage = mutableMapOf<String, Long>()
        private val lastScreenshotTimeByPackage = mutableMapOf<String, Long>()
        private val screenshotInProgress = AtomicBoolean(false)
        @Volatile private var activeService: CaptureAccessibilityService? = null
        private val TARGET_APPS = mapOf(
            "com.instagram.android" to "Instagram",
            "com.whatsapp" to "WhatsApp",
            "com.facebook.katana" to "Facebook",
        )

        fun notifyAuthTokenAvailable() {
            activeService?.captureQueueBridge?.onAuthTokenAvailable()
        }

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
        activeService = this
        Log.i(TAG, "Capture accessibility service connected")
        if (persistentInitializationGate.tryStart()) {
            initializePersistentCapturePipeline()
        } else {
            captureQueueBridge?.onServiceStarted()
        }
    }
    override fun onUnbind(intent: Intent?) : Boolean {
        AccessibilityState.setRunning(false)
        if (activeService === this) activeService = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        // Catch-all for when the service is destroyed
        AccessibilityState.setRunning(false)
        if (activeService === this) activeService = null
        persistentInitializationGate.close()
        val (bridge, queue) = synchronized(persistentLifecycleLock) {
            serviceDestroyed = true
            val resources = captureQueueBridge to persistentQueue
            captureQueueBridge = null
            persistentQueue = null
            resources
        }
        val discardedCaptures = captureInitializationBuffer.close()
        if (discardedCaptures > 0) {
            Log.w(TAG, "Discarded $discardedCaptures volatile capture(s) during service teardown")
        }
        bridge?.close()
        serviceScope.cancel()
        queue?.let {
            CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
                try {
                    it.close()
                } catch (error: Exception) {
                    Log.e(TAG, "Persistent capture queue close failed: ${error.javaClass.simpleName}")
                }
            }
        }
        super.onDestroy()
    }

    @RequiresApi(Build.VERSION_CODES.R)
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (AccessibilityState.isPaused.value) return
        if (event == null) return

        val packageName = event.packageName?.toString() ?: return
        val appName = TARGET_APPS[packageName] ?: return
        val now = System.currentTimeMillis()
        val capturedAt = OffsetDateTime.now()
        val capturedEventName = eventName(event.eventType)
        val lastSummaryTime = lastSummaryTimeByPackage[packageName] ?: 0L
        val shouldSendSummary = now - lastSummaryTime >= SUMMARY_THROTTLE_MS

        val lastScreenshotTime = lastScreenshotTimeByPackage[packageName] ?: 0L
        val shouldSendScreenshot = now - lastScreenshotTime >= SCREENSHOT_THROTTLE_MS

        if (!shouldSendSummary && !shouldSendScreenshot) return
        Log.d(TAG, "Captured event for $appName: $capturedEventName")

        val screenSummary = collectScreenSummary()
        Log.d(
            TAG,
            "Captured screen summary: app=$appName, event=$capturedEventName, " +
                "package=$packageName, nodeCount=${screenSummary.nodeCount}, " +
                "textCount=${screenSummary.texts.size}"
        )
        if (shouldSendSummary) {
            lastSummaryTimeByPackage[packageName] = now
            val queuedCapture = AccessibilityCaptureMapper.map(
                packageName = packageName,
                appName = appName,
                eventType = capturedEventName,
                screenSummary = screenSummary,
                isTargetApp = TARGET_APPS.containsKey(packageName),
                isSupportedEventType = event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
                    event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
                capturedAt = capturedAt,
            )
            val submission = captureInitializationBuffer.submit(queuedCapture) {
                // The legacy endpoint remains transitional, but never runs before queue persistence.
                network_man.sendCaptureSummary(
                    packageName = packageName,
                    appName = appName,
                    eventType = capturedEventName,
                    screenSummary = screenSummary,
                )
            }
            when (submission) {
                CaptureSubmissionResult.BUFFERED_AFTER_DROPPING_OLDEST ->
                    Log.w(TAG, "Capture initialization buffer was full; its oldest capture was discarded")
                CaptureSubmissionResult.UNAVAILABLE ->
                    Log.w(TAG, "Persistent capture pipeline is unavailable; capture was not submitted")
                CaptureSubmissionResult.SUBMITTED,
                CaptureSubmissionResult.BUFFERED,
                -> Unit
            }
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

    private fun initializePersistentCapturePipeline() {
        serviceScope.launch {
            var openedQueue: PersistentEventQueue? = null
            var bridge: CaptureQueueBridge? = null
            try {
                openedQueue = PersistentEventQueue.open(applicationContext)
                val coordinator = UploadCoordinator(
                    tokenStore = NativeTokenStore(applicationContext),
                    queue = openedQueue,
                    apiClient = CapturesApiClient(),
                )
                bridge = CaptureQueueBridge(
                    scope = serviceScope,
                    queue = openedQueue,
                    uploader = coordinator,
                    diagnosticLogger = { message -> Log.w(TAG, message) },
                    pipelineLogger = { message -> Log.i(PHASE5A_TAG, message) },
                )
                val installed = synchronized(persistentLifecycleLock) {
                    if (serviceDestroyed) {
                        false
                    } else {
                        persistentQueue = openedQueue
                        captureQueueBridge = bridge
                        true
                    }
                }
                if (installed) {
                    openedQueue = null
                    val attachResult = captureInitializationBuffer.attach(bridge)
                    if (attachResult.rejectedCount > 0) {
                        Log.w(TAG, "Persistent bridge rejected ${attachResult.rejectedCount} buffered capture(s)")
                    }
                    bridge.onServiceStarted()
                    persistentInitializationGate.initializationSucceeded()
                    bridge = null
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.e(TAG, "Persistent capture pipeline initialization failed: ${error.javaClass.simpleName}")
                synchronized(persistentLifecycleLock) {
                    if (captureQueueBridge === bridge) captureQueueBridge = null
                    if (openedQueue == null && persistentQueue != null) {
                        openedQueue = persistentQueue
                        persistentQueue = null
                    }
                }
                persistentInitializationGate.initializationFailed()
            } finally {
                withContext(NonCancellable) {
                    bridge?.close()
                    openedQueue?.close()
                }
            }
        }
    }




}
