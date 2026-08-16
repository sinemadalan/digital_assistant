package com.example.accessibility_service.Util
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.time.OffsetDateTime
import android.graphics.Bitmap
import android.os.Build
import androidx.annotation.RequiresApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext


private const val TAG = "CaptureA11yService"
private const val HTTP_TIMEOUT_MS = 3_000

class NetworkSyncManager(val IP: String){

    fun CaptureNode.toJson(): JSONObject {
        return JSONObject()
            .put("text", text)
            .put("contentDescription", contentDescription)
            .put("className", className)
            .put("viewIdResourceName", viewIdResourceName)
            .put("isClickable", isClickable)
            .put("isEditable", isEditable)
    }


    fun sendCaptureSummary(
        packageName: String,
        appName: String,
        eventType: String,
        screenSummary: ScreenSummary,
    ) {
        val payload = JSONObject()
            .put("packageName", packageName)
            .put("appName", appName)
            .put("eventType", eventType)
            .put("capturedAtDevice", OffsetDateTime.now().toString())
            .put("screenText", JSONArray(screenSummary.texts))
            .put("nodes", JSONArray(screenSummary.nodes.map { node -> node.toJson() }))

        Thread {
            try {
                val connection = URL("$IP/capture").openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.connectTimeout = HTTP_TIMEOUT_MS
                connection.readTimeout = HTTP_TIMEOUT_MS
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")

                OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { writer ->
                    writer.write(payload.toString())
                }

                val responseCode = connection.responseCode
                Log.d(TAG, "Capture POST response: $responseCode")
                connection.disconnect()
            } catch (error: Exception) {
                Log.e(TAG, "Capture POST failed: ${error.message}", error)
            }
        }.start()
    }

    @RequiresApi(Build.VERSION_CODES.R)
    suspend fun sendScreenshot(imageBytes: ByteArray, userId: String) {
        withContext(Dispatchers.IO) {
            var conn: HttpURLConnection? = null
            try {
                Log.d(TAG, "Sending data")
                val url = URL("$IP/image_capture")
                Log.d(TAG, url.toString())
                val connection = url.openConnection() as HttpURLConnection
                conn = connection
                connection.connectTimeout = HTTP_TIMEOUT_MS
                connection.readTimeout = HTTP_TIMEOUT_MS
                connection.setDoOutput(true)
                connection.setRequestMethod("POST")
                connection.setRequestProperty("Content-Type", "image/jpeg")
                // 2. Pass your metadata via Custom Headers (usually prefixed with X-)
                connection.setRequestProperty("X-User-Id", userId)
                connection.getOutputStream().use { os ->
                    os.write(imageBytes)
                    os.flush()
                }
                val code = connection.responseCode
                Log.d(TAG, "Success send ss $code")
            }
            catch (error: Exception){
                Log.e(TAG, "Screenshot POST failed: ${error.message}", error)
            } finally {
                conn?.disconnect()
            }
        }
    }

}
