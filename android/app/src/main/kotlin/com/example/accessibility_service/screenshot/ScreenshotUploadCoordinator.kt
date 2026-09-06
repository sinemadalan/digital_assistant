package com.example.accessibility_service.screenshot

import android.util.Log
import com.example.accessibility_service.NativeTokenStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class ScreenshotUploadCoordinator(
    private val tokenStore: NativeTokenStore,
    private val queue: ScreenshotQueue,
    private val apiClient: ScreenshotApiClient,
    private val logger: (String) -> Unit = { Log.i(TAG, it) }
) {
    private val uploadMutex = Mutex()

    suspend fun requestUpload() {
        if (!uploadMutex.tryLock()) {
            logger("Upload already in progress, skipping request")
            return
        }
        
        try {
            uploadAvailableScreenshots()
        } finally {
            uploadMutex.unlock()
        }
    }

    private suspend fun uploadAvailableScreenshots() {
        while (true) {
            val token = tokenStore.getToken()
            if (token.isNullOrBlank()) {
                logger("No valid token available, halting uploads")
                return
            }

            val screenshot = queue.peekScreenshot() ?: return

            logger("Attempting to upload screenshot: ${screenshot.id}")
            val result = apiClient.uploadScreenshot(token, screenshot)

            when (result) {
                is ScreenshotUploadResult.Success -> {
                    logger("Successfully uploaded screenshot: ${screenshot.id}")
                    queue.acknowledge(screenshot.id)
                }
                is ScreenshotUploadResult.Unauthorized -> {
                    logger("Token unauthorized, revoking and halting screenshot uploads")
                    tokenStore.clearToken()
                    return
                }
                is ScreenshotUploadResult.NotFound -> {
                    logger("Endpoint returned 404 (not implemented yet). Retaining screenshot ${screenshot.id} on disk for ADB inspection and halting uploads")
                    return
                }
                is ScreenshotUploadResult.NetworkError,
                is ScreenshotUploadResult.Timeout,
                is ScreenshotUploadResult.ServerError -> {
                    logger("Retryable error uploading screenshot ${screenshot.id}, halting until next trigger")
                    return
                }
                is ScreenshotUploadResult.OtherHttpError -> {
                    if (result.statusCode == 404) {
                        logger("Endpoint returned 404. Retaining screenshot ${screenshot.id} on disk for ADB inspection and halting uploads")
                        return
                    }
                    logger("Non-retryable HTTP error ${result.statusCode} for screenshot ${screenshot.id}, discarding")
                    queue.acknowledge(screenshot.id)
                }
            }
        }
    }

    companion object {
        private const val TAG = "ScreenshotUploadCoord"
    }
}
