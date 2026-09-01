package com.example.accessibility_service.networking

import com.example.accessibility_service.persistence.QueuedCaptureNode
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CapturesJsonSerializerTest {
    @Test
    fun singleEventUsesExactBackendFieldNamesAndValues() {
        val root = JSONObject(CapturesJsonSerializer.serialize(listOf(sampleCapture())))
        val event = root.getJSONArray("events").getJSONObject(0)

        assertEquals("com.example.app0", event.getString("packageName"))
        assertEquals("Example 0", event.getString("appName"))
        assertEquals("TYPE_WINDOW_CONTENT_CHANGED", event.getString("eventType"))
        assertEquals("2026-09-01T12:00:00+03:00", event.getString("capturedAtDevice"))
        assertEquals(listOf("Uninstall", "Cancel", "Instagram"), event.getJSONArray("screenText").toStringList())
        assertTrue(event.getBoolean("isTargetApp"))
        assertTrue(event.getBoolean("isSupportedEventType"))
        assertEquals(
            setOf(
                "packageName", "appName", "eventType", "capturedAtDevice", "screenText", "nodes",
                "isTargetApp", "isSupportedEventType",
            ),
            event.keyNames(),
        )
        assertFalse(root.has("device_id"))
        assertFalse(root.has("user_id"))
    }

    @Test
    fun serializesThirtyAndFiftyEvents() {
        assertEquals(30, serializeCount(30))
        assertEquals(50, serializeCount(50))
    }

    @Test
    fun nullableNodeFieldsRemainJsonNullAndNamesAreExact() {
        val event = JSONObject(
            CapturesJsonSerializer.serialize(
                listOf(sampleCapture(nodes = listOf(QueuedCaptureNode()))),
            ),
        ).getJSONArray("events").getJSONObject(0)
        val node = event.getJSONArray("nodes").getJSONObject(0)

        assertSame(JSONObject.NULL, node.get("text"))
        assertSame(JSONObject.NULL, node.get("contentDescription"))
        assertSame(JSONObject.NULL, node.get("className"))
        assertSame(JSONObject.NULL, node.get("viewIdResourceName"))
        assertFalse(node.getBoolean("isClickable"))
        assertFalse(node.getBoolean("isEditable"))
        assertEquals(
            setOf(
                "text", "contentDescription", "className", "viewIdResourceName", "isClickable", "isEditable",
            ),
            node.keyNames(),
        )
    }

    @Test
    fun preservesTurkishEmojiAndScreenTextArray() {
        val expected = listOf("İstanbul'da şifre", "Merhaba 👋🌍")
        val event = JSONObject(
            CapturesJsonSerializer.serialize(listOf(sampleCapture(screenText = expected))),
        ).getJSONArray("events").getJSONObject(0)

        assertEquals(expected, event.getJSONArray("screenText").toStringList())
    }

    private fun serializeCount(count: Int): Int = JSONObject(
        CapturesJsonSerializer.serialize(List(count) { sampleCapture(it) }),
    ).getJSONArray("events").length()

    private fun org.json.JSONArray.toStringList(): List<String> =
        List(length()) { index -> getString(index) }

    private fun JSONObject.keyNames(): Set<String> {
        val result = mutableSetOf<String>()
        val iterator = keys()
        while (iterator.hasNext()) result += iterator.next()
        return result
    }
}
