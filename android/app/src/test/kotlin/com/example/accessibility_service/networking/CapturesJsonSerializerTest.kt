package com.example.accessibility_service.networking

import com.example.accessibility_service.Util.BoundsInScreen
import com.example.accessibility_service.persistence.QueuedCaptureNode
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class CapturesJsonSerializerTest {
    @Test
    fun structuralTreePreservesOrderDuplicatesGeometryAndRootPackage() {
        val bounds = BoundsInScreen(-100, -500, 1080, 2500)
        val root = QueuedCaptureNode(className = "root", visibleToUser = true,
            boundsInScreen = bounds, parentIndex = null)
        val caption = root.copy(className = "caption", text = "caption", parentIndex = 1)
        val capture = sampleCapture(nodes = listOf(
            root, root.copy(className = "container", visibleToUser = false, parentIndex = 0),
            caption, caption,
        )).copy(rootPackageName = "root.package", windowBoundsInScreen = bounds)
        val event = JSONObject(CapturesJsonSerializer.serialize(listOf(capture)))
            .getJSONArray("events").getJSONObject(0)
        assertEquals(capture.packageName, event.getString("packageName"))
        assertEquals("root.package", event.getString("rootPackageName"))
        assertBounds(bounds, event.getJSONObject("windowBoundsInScreen"))
        val nodes = event.getJSONArray("nodes")
        assertEquals(4, nodes.length())
        capture.nodes.forEachIndexed { index, expected ->
            val actual = nodes.getJSONObject(index)
            assertEquals(expected.className, actual.getString("className"))
            assertEquals(expected.visibleToUser, actual.getBoolean("visibleToUser"))
            assertBounds(expected.boundsInScreen, actual.getJSONObject("boundsInScreen"))
            if (expected.parentIndex == null) assertSame(JSONObject.NULL, actual.get("parentIndex"))
            else assertEquals(expected.parentIndex, actual.getInt("parentIndex"))
        }
        assertSame(JSONObject.NULL, nodes.getJSONObject(1).get("text"))
        assertSame(JSONObject.NULL, nodes.getJSONObject(1).get("contentDescription"))
        assertEquals(capture.screenText, event.getJSONArray("screenText").toStringList())
    }

    @Test
    fun invalidParentIndicesAreRejectedWithoutRewritingTheTree() {
        for (invalid in listOf(-1, 1)) {
            val node = QueuedCaptureNode(visibleToUser = true,
                boundsInScreen = BoundsInScreen(0, 0, 1, 1), parentIndex = invalid)
            assertThrows(IllegalArgumentException::class.java) {
                CapturesJsonSerializer.serialize(listOf(sampleCapture(nodes = listOf(node))))
            }
        }
    }

    private fun assertBounds(expected: BoundsInScreen, actual: JSONObject) {
        assertEquals(setOf("left", "top", "right", "bottom"), actual.keyNames())
        assertEquals(expected.left, actual.getInt("left"))
        assertEquals(expected.top, actual.getInt("top"))
        assertEquals(expected.right, actual.getInt("right"))
        assertEquals(expected.bottom, actual.getInt("bottom"))
    }

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
                "isTargetApp", "isSupportedEventType", "rootPackageName", "windowBoundsInScreen",
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
                listOf(sampleCapture(nodes = listOf(QueuedCaptureNode(
                    visibleToUser = false,
                    boundsInScreen = BoundsInScreen(-100, -500, 1080, 2500),
                    parentIndex = null,
                )))),
            ),
        ).getJSONArray("events").getJSONObject(0)
        val node = event.getJSONArray("nodes").getJSONObject(0)

        assertSame(JSONObject.NULL, node.get("text"))
        assertSame(JSONObject.NULL, node.get("contentDescription"))
        assertSame(JSONObject.NULL, node.get("className"))
        assertSame(JSONObject.NULL, node.get("viewIdResourceName"))
        assertFalse(node.getBoolean("isClickable"))
        assertFalse(node.getBoolean("isEditable"))
        assertFalse(node.getBoolean("visibleToUser"))
        assertSame(JSONObject.NULL, node.get("parentIndex"))
        assertSame(JSONObject.NULL, event.get("rootPackageName"))
        assertSame(JSONObject.NULL, event.get("windowBoundsInScreen"))
        assertEquals(
            setOf(
                "text", "contentDescription", "className", "viewIdResourceName", "isClickable", "isEditable",
                "visibleToUser", "boundsInScreen", "parentIndex",
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
