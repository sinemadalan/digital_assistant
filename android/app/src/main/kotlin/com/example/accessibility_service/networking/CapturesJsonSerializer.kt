package com.example.accessibility_service.networking

import com.example.accessibility_service.persistence.QueuedCapture
import com.example.accessibility_service.persistence.QueuedCaptureNode
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

internal object CapturesJsonSerializer {
    fun serialize(captures: List<QueuedCapture>): String = JSONObject()
        .put("events", JSONArray(captures.map(::captureToJson)))
        .toString()

    private fun captureToJson(capture: QueuedCapture): JSONObject = JSONObject()
        .put("packageName", capture.packageName)
        .put("appName", capture.appName)
        .put("eventType", capture.eventType)
        .put("capturedAtDevice", capture.capturedAtDevice)
        .put("screenText", JSONArray(capture.screenText))
        .put("nodes", JSONArray(capture.nodes.map(::nodeToJson)))
        .put("isTargetApp", capture.isTargetApp)
        .put("isSupportedEventType", capture.isSupportedEventType)

    private fun nodeToJson(node: QueuedCaptureNode): JSONObject = JSONObject()
        .putNullable("text", node.text)
        .putNullable("contentDescription", node.contentDescription)
        .putNullable("className", node.className)
        .putNullable("viewIdResourceName", node.viewIdResourceName)
        .put("isClickable", node.isClickable)
        .put("isEditable", node.isEditable)

    private fun JSONObject.putNullable(name: String, value: String?): JSONObject =
        put(name, value ?: JSONObject.NULL)
}

internal object CapturesResponseParser {
    fun parse(body: String, sentEventCount: Int): CapturesResponse {
        val root = try {
            JSONObject(body)
        } catch (error: JSONException) {
            throw InvalidCapturesResponseException("Response is not valid JSON", error)
        }

        val accepted = root.requiredInt("accepted")
        val skipped = root.requiredInt("skipped")
        if (accepted < 0 || skipped < 0) {
            throw InvalidCapturesResponseException("accepted and skipped must be non-negative")
        }
        if (accepted.toLong() + skipped.toLong() != sentEventCount.toLong()) {
            throw InvalidCapturesResponseException("accepted plus skipped must equal the sent event count")
        }

        val config = root.requiredObject("config")
        val commands = root.requiredArray("commands")
        return CapturesResponse(
            accepted = accepted,
            skipped = skipped,
            config = CapturesConfig(
                batchSize = config.requiredInt("batch_size"),
                flushSeconds = config.requiredInt("flush_seconds"),
            ),
            commands = List(commands.length()) { index ->
                RawCaptureCommand(commands.get(index).toRawJson())
            },
        )
    }

    private fun Any?.toRawJson(): String = when (this) {
        null, JSONObject.NULL -> "null"
        is JSONObject, is JSONArray -> toString()
        is String -> JSONObject.quote(this)
        is Number, is Boolean -> toString()
        else -> throw InvalidCapturesResponseException("Command contains an unsupported JSON value")
    }

    private fun JSONObject.requiredInt(name: String): Int {
        if (!has(name) || isNull(name)) {
            throw InvalidCapturesResponseException("Missing integer field: $name")
        }
        val value = get(name)
        val longValue = when (value) {
            is Byte -> value.toLong()
            is Short -> value.toLong()
            is Int -> value.toLong()
            is Long -> value
            else -> throw InvalidCapturesResponseException("Field is not an integer: $name")
        }
        if (longValue !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
            throw InvalidCapturesResponseException("Integer field is out of range: $name")
        }
        return longValue.toInt()
    }

    private fun JSONObject.requiredObject(name: String): JSONObject = try {
        getJSONObject(name)
    } catch (error: JSONException) {
        throw InvalidCapturesResponseException("Missing or invalid object field: $name", error)
    }

    private fun JSONObject.requiredArray(name: String): JSONArray = try {
        getJSONArray(name)
    } catch (error: JSONException) {
        throw InvalidCapturesResponseException("Missing or invalid array field: $name", error)
    }
}

internal class InvalidCapturesResponseException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
