package com.example.accessibility_service.Util
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.time.OffsetDateTime


private const val TAG = "CaptureA11yService"
private const val HTTP_TIMEOUT_MS = 3_000

class NetworkSyncManager(val endpoint: String){

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
                val connection = URL(endpoint).openConnection() as HttpURLConnection
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

}