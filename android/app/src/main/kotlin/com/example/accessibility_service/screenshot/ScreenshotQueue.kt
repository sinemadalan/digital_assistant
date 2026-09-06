package com.example.accessibility_service.screenshot

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

interface ScreenshotQueue {
    suspend fun enqueue(bitmap: Bitmap, packageName: String): EnqueueResult
    suspend fun peekScreenshot(): QueuedScreenshot?
    suspend fun acknowledge(screenshotId: String)
}

data class QueuedScreenshot(
    val id: String,
    val file: File,
    val packageName: String
)

sealed interface EnqueueResult {
    data object Enqueued : EnqueueResult
    data object DroppedOldest : EnqueueResult
    data class Error(val exception: Exception) : EnqueueResult
}

class PersistentScreenshotQueue(
    private val context: Context,
    private val maxFiles: Int = 20,
    private val jpegQuality: Int = 60,
    private val downscaleFactor: Float = 0.5f,
    private val logger: (String) -> Unit = { Log.i(TAG, it) }
) : ScreenshotQueue {

    private val directory = File(context.filesDir, "screenshots").apply { mkdirs() }
    
    // In-memory representation of queued files
    private val queuedFiles = mutableListOf<QueuedScreenshot>()

    init {
        // Load existing files on startup
        val files = directory.listFiles()?.filter { it.isFile && it.name.endsWith(".jpg") } ?: emptyList()
        files.sortedBy { it.lastModified() }.forEach { file ->
            val parts = file.nameWithoutExtension.split("_", limit = 2)
            val packageName = if (parts.size == 2) parts[0] else "unknown"
            queuedFiles.add(QueuedScreenshot(file.name, file, packageName))
        }
    }

    override suspend fun enqueue(bitmap: Bitmap, packageName: String): EnqueueResult = withContext(Dispatchers.IO) {
        try {
            var dropped = false
            if (queuedFiles.size >= maxFiles) {
                // Delete oldest
                val oldest = queuedFiles.removeAt(0)
                if (oldest.file.exists()) {
                    oldest.file.delete()
                }
                dropped = true
                logger("Dropped oldest screenshot to respect maxFiles limit: ${oldest.file.name}")
            }

            val scaledBitmap = if (downscaleFactor < 1.0f) {
                Bitmap.createScaledBitmap(
                    bitmap, 
                    (bitmap.width * downscaleFactor).toInt(), 
                    (bitmap.height * downscaleFactor).toInt(), 
                    true
                )
            } else {
                bitmap
            }

            val filename = "${packageName}_${UUID.randomUUID()}.jpg"
            val file = File(directory, filename)
            
            file.outputStream().use { output ->
                scaledBitmap.compress(Bitmap.CompressFormat.JPEG, jpegQuality, output)
            }
            
            if (scaledBitmap != bitmap) {
                scaledBitmap.recycle()
            }

            queuedFiles.add(QueuedScreenshot(filename, file, packageName))
            
            if (dropped) EnqueueResult.DroppedOldest else EnqueueResult.Enqueued
        } catch (e: Exception) {
            logger("Failed to enqueue screenshot: ${e.message}")
            EnqueueResult.Error(e)
        }
    }

    override suspend fun peekScreenshot(): QueuedScreenshot? = withContext(Dispatchers.IO) {
        queuedFiles.firstOrNull()
    }

    override suspend fun acknowledge(screenshotId: String) = withContext(Dispatchers.IO) {
        val iterator = queuedFiles.iterator()
        while (iterator.hasNext()) {
            val item = iterator.next()
            if (item.id == screenshotId) {
                if (item.file.exists()) {
                    item.file.delete()
                }
                iterator.remove()
                logger("Acknowledged and deleted screenshot: $screenshotId")
                break
            }
        }
    }

    companion object {
        private const val TAG = "ScreenshotQueue"
    }
}
