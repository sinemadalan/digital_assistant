package com.example.accessibility_service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.annotation.RequiresApi
import com.example.accessibility_service.Util.NodeWalker
import com.example.accessibility_service.Util.BoundsInScreen
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.example.accessibility_service.screenshot.PersistentScreenshotQueue
import com.example.accessibility_service.screenshot.ScreenshotUploadCoordinator
import com.example.accessibility_service.screenshot.MultipartScreenshotApiClient
import java.time.OffsetDateTime
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.ConcurrentHashMap

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

    @Volatile
    private var persistentQueue: PersistentEventQueue? = null

    @Volatile
    private var captureQueueBridge: CaptureQueueBridge? = null

    @Volatile
    private var screenshotQueue: PersistentScreenshotQueue? = null

    @Volatile
    private var screenshotUploadCoordinator: ScreenshotUploadCoordinator? = null
    private val debounceJobs = ConcurrentHashMap<String, Job>()
    private val persistentInitializationGate by lazy {
        PipelineInitializationRetryGate(
            scope = serviceScope,
            initialize = ::initializePersistentCapturePipeline,
            pipelineLogger = { message -> Log.i(PHASE5A_TAG, message) },
        )
    }

    companion object {
        private val nodewalker = NodeWalker();
        private const val TAG = "CaptureA11yService"
        private const val PHASE5A_TAG = "Phase5A"
        private const val SUMMARY_THROTTLE_MS = 5_000L
        private const val SCREENSHOT_DEBOUNCE_MS = 1_000L
        private val lastSummaryTimeByPackage = mutableMapOf<String, Long>()
        private val screenshotInProgress = AtomicBoolean(false)
        private val lastMeaningfulEventTimeByPackage = ConcurrentHashMap<String, AtomicLong>()
        private val isPackageSettled = ConcurrentHashMap<String, Boolean>()

        @Volatile
        private var activeService: CaptureAccessibilityService? = null
        private val TARGET_APPS = mapOf(
            "com.instagram.android" to "Instagram",
            "com.whatsapp" to "WhatsApp",
            "com.facebook.katana" to "Facebook",
        )

        fun notifyAuthTokenAvailable() {
            activeService?.captureQueueBridge?.onAuthTokenAvailable()
        }

        private fun eventName(eventType: Int): String {
            return try {
                AccessibilityEvent.eventTypeToString(eventType)
            } catch (_: Throwable) {
                when (eventType) {
                    AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> "TYPE_WINDOW_STATE_CHANGED"
                    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> "TYPE_WINDOW_CONTENT_CHANGED"
                    AccessibilityEvent.TYPE_VIEW_SCROLLED -> "TYPE_VIEW_SCROLLED"
                    AccessibilityEvent.TYPE_VIEW_CLICKED -> "TYPE_VIEW_CLICKED"
                    else -> "TYPE_$eventType"
                }
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

    override fun onUnbind(intent: Intent?): Boolean {
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
            screenshotQueue = null
            screenshotUploadCoordinator = null
            resources
        }
        debounceJobs.values.forEach { it.cancel() }
        debounceJobs.clear()
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

        // if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
        //     logWindowContentChanged(event, appName)
        // }

        // --- SCREENSHOT LOGIC ---
        val now = System.currentTimeMillis()
        val isIgnorable = isIgnorableEvent(event)

        val lastMeaningfulEventTime = lastMeaningfulEventTimeByPackage.getOrPut(packageName) { AtomicLong(now) }
        if (!isIgnorable) {
            lastMeaningfulEventTime.set(now)
        }

        val activeJob = debounceJobs[packageName]
        if (activeJob == null || !activeJob.isActive) {
            // No job running, or it finished. Launch a new one.
            debounceJobs[packageName] = serviceScope.launch {
                monitorScreenSettle(packageName, lastMeaningfulEventTime)
            }
        } else if (isPackageSettled[packageName] == true && !isIgnorable) {
            // Screen was settled (watching video), but now it's moving again.
            // Cancel the video timer and restart the debounce monitor.
            activeJob.cancel()
            debounceJobs[packageName] = serviceScope.launch {
                monitorScreenSettle(packageName, lastMeaningfulEventTime)
            }
        }
        // If the job is active AND it's NOT settled, we do absolutely nothing!
        // The active job will naturally wake up, see the updated lastEventTime, and sleep again.

        // --- SUMMARY LOGIC ---
        val lastSummaryTime = lastSummaryTimeByPackage[packageName] ?: 0L
        val shouldSendSummary = now - lastSummaryTime >= SUMMARY_THROTTLE_MS

        if (!shouldSendSummary) return

        lastSummaryTimeByPackage[packageName] = now
        val capturedAt = OffsetDateTime.now()
        val capturedEventName = eventName(event.eventType)

        Log.d(TAG, "Captured event for $appName: $capturedEventName")

        val screenSummary = collectScreenSummary()
        Log.d(
            TAG,
            "Captured screen summary: app=$appName, event=$capturedEventName, " +
                    "package=$packageName, nodeCount=${screenSummary.nodeCount}, " +
                    "textCount=${screenSummary.texts.size}"
        )

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
        val submission = captureInitializationBuffer.submit(queuedCapture)
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

    private suspend fun monitorScreenSettle(packageName: String, lastMeaningfulEventTime: AtomicLong) {
        isPackageSettled[packageName] = false

        // 1. Debounce Phase (Wait for Screen to Settle)
        while (true) {
            val timeSinceLastEvent = System.currentTimeMillis() - lastMeaningfulEventTime.get()
            if (timeSinceLastEvent >= SCREENSHOT_DEBOUNCE_MS) {
                break // Screen has settled!
            }
            delay(SCREENSHOT_DEBOUNCE_MS - timeSinceLastEvent)
        }

        // 2. Screen Settled! Take one-shot screenshot.
        Log.d(TAG, "SCREENSHOT: Screen has settled, taking screenshot")
        triggerScreenshot(packageName)
        isPackageSettled[packageName] = true

        // 3. Periodic Video Timer Phase
        // Keep this modular so it can be easily commented out or disabled if needed.
        while (true) {
            delay(60_000L) // 1 minute timer for video watching
            if (AccessibilityState.isPaused.value) continue

            Log.d(TAG, "SCREENSHOT: Periodic video screenshot triggered for $packageName")
            triggerScreenshot(packageName)
        }
    }

    private fun triggerScreenshot(packageName: String) {
        if (!screenshotInProgress.compareAndSet(false, true)) return

        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
            override fun onFailure(p0: Int) {
                screenshotInProgress.set(false)
                Log.e(TAG, "SCREENSHOT: Error taking screenshot $p0")
            }

            override fun onSuccess(screenshotResult: ScreenshotResult) {
                Log.d(TAG, "Success taking screenshot")
                serviceScope.launch {
                    var bitmap: Bitmap? = null
                    try {
                        Log.d(TAG, "SCREENSHOT: Processing image")
                        bitmap = Bitmap.wrapHardwareBuffer(
                            screenshotResult.hardwareBuffer,
                            screenshotResult.colorSpace,
                        ) ?: throw IllegalStateException("Could not create bitmap from screenshot")

                        screenshotQueue?.enqueue(bitmap, packageName)
                        screenshotUploadCoordinator?.requestUpload()
                    } catch (error: Exception) {
                        Log.e(TAG, "SCREENSHOT: Screenshot processing failed: ${error.message}", error)
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
        val rootPackageName = rootNode.packageName?.toString()
        val windowBounds = readWindowBounds(rootNode)
        return nodewalker.walk(rootNode).copy(
            rootPackageName = rootPackageName,
            windowBoundsInScreen = windowBounds,
        )
    }

    private fun readWindowBounds(rootNode: AccessibilityNodeInfo): BoundsInScreen? {
        // Use the root's own window, not a later active-window snapshot.
        return try {
            val window = rootNode.window ?: return null
            try {
                val bounds = Rect()
                window.getBoundsInScreen(bounds)
                BoundsInScreen(bounds.left, bounds.top, bounds.right, bounds.bottom)
            } finally {
                @Suppress("DEPRECATION")
                window.recycle()
            }
        } catch (_: RuntimeException) {
            // Unavailable window metadata must not discard the capture.
            null
        }
    }

    private fun logWindowContentChanged(event: AccessibilityEvent, appName: String) {
        try {
            val changeTypes = describeContentChangeTypes(event.contentChangeTypes)
            val sourceNode = try {
                event.source
            } catch (_: Throwable) {
                null
            }
            try {
                val sourceDetails = if (sourceNode != null) {
                    val bounds = Rect()
                    try {
                        sourceNode.getBoundsInScreen(bounds)
                    } catch (_: Throwable) {
                    }
                    val viewId = try {
                        sourceNode.viewIdResourceName ?: "none"
                    } catch (_: Throwable) {
                        "unknown"
                    }
                    val className = try {
                        sourceNode.className?.toString() ?: "unknown"
                    } catch (_: Throwable) {
                        "unknown"
                    }
                    val textPreview = try {
                        sourceNode.text?.let { "\"${it.toString().replace("\n", " ").take(60)}\"" } ?: "null"
                    } catch (_: Throwable) {
                        "null"
                    }
                    val descPreview = try {
                        sourceNode.contentDescription?.let { "\"$it\"" } ?: "null"
                    } catch (_: Throwable) {
                        "null"
                    }
                    val stateDesc = if (Build.VERSION.SDK_INT >= 30) {
                        try {
                            sourceNode.stateDescription?.let { "\"$it\"" } ?: "null"
                        } catch (_: Throwable) {
                            "null"
                        }
                    } else {
                        "N/A"
                    }
                    val isClickable = try {
                        sourceNode.isClickable
                    } catch (_: Throwable) {
                        false
                    }
                    val isEnabled = try {
                        sourceNode.isEnabled
                    } catch (_: Throwable) {
                        false
                    }
                    val isScrollable = try {
                        sourceNode.isScrollable
                    } catch (_: Throwable) {
                        false
                    }

                    "viewId=$viewId, class=$className, text=$textPreview, desc=$descPreview, " +
                            "state=$stateDesc, bounds=[${bounds.left},${bounds.top}][${bounds.right},${bounds.bottom}], " +
                            "clickable=$isClickable, scrollable=$isScrollable, enabled=$isEnabled"
                } else {
                    "source=null (root/window-level change or node recycled)"
                }

                val eventText = if (event.text.isNotEmpty()) " | eventText=${event.text}" else ""
                Log.i(TAG, "[$appName] WINDOW_CONTENT_CHANGED -> types=[$changeTypes] | $sourceDetails$eventText")
            } finally {
                try {
                    @Suppress("DEPRECATION")
                    sourceNode?.recycle()
                } catch (_: Throwable) {
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "[$appName] Error in logWindowContentChanged: ${t.message}", t)
        }
    }

    private fun describeContentChangeTypes(changeTypes: Int): String {
        if (changeTypes == 0) return "CONTENT_CHANGE_TYPE_UNDEFINED"
        val types = mutableListOf<String>()
        if (changeTypes and 1 != 0) types.add("SUBTREE")
        if (changeTypes and 2 != 0) types.add("TEXT")
        if (changeTypes and 4 != 0) types.add("CONTENT_DESCRIPTION")
        if (changeTypes and 8 != 0) types.add("PANE_TITLE")
        if (changeTypes and 16 != 0) types.add("PANE_APPEARED")
        if (changeTypes and 32 != 0) types.add("PANE_DISAPPEARED")
        if (changeTypes and 64 != 0) types.add("STATE_DESCRIPTION")
        if (changeTypes and 128 != 0) types.add("DRAG_STARTED")
        if (changeTypes and 256 != 0) types.add("DRAG_DROPPED")
        if (changeTypes and 512 != 0) types.add("DRAG_CANCELLED")
        if (changeTypes and 1024 != 0) types.add("ERROR")
        if (changeTypes and 2048 != 0) types.add("ENABLED")
        return if (types.isEmpty()) "UNKNOWN ($changeTypes)" else types.joinToString("|")
    }

    private fun isIgnorableEvent(event: AccessibilityEvent): Boolean {
        // Fast path check directly on event properties to avoid IPC cost of event.source
        val className = event.className?.toString() ?: return false
        return when (className) {
            "android.widget.SeekBar",
            "android.widget.ProgressBar" -> true

            else -> false
        }
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

                        // Initialize screenshot pipeline
                        screenshotQueue = PersistentScreenshotQueue(applicationContext)
                        screenshotUploadCoordinator = ScreenshotUploadCoordinator(
                            tokenStore = NativeTokenStore(applicationContext),
                            queue = screenshotQueue!!,
                            apiClient = MultipartScreenshotApiClient()
                        )
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
                    screenshotUploadCoordinator?.requestUpload()
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
