package com.example.accessibility_service.upload

import com.example.accessibility_service.Util.CaptureNode
import com.example.accessibility_service.Util.ScreenSummary
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilityCaptureMapperTest {
    @Test
    fun mapsSummaryAndNodeFieldsWithoutMergingModels() {
        val capturedAt = OffsetDateTime.parse("2026-09-02T10:15:30.123+03:00")
        val summary = ScreenSummary(
            nodeCount = 7,
            texts = listOf("first", "second"),
            nodes = listOf(
                CaptureNode(
                    text = "node text",
                    contentDescription = "description",
                    className = "android.widget.EditText",
                    viewIdResourceName = "com.whatsapp:id/message",
                    isClickable = true,
                    isEditable = true,
                ),
            ),
        )

        val capture = AccessibilityCaptureMapper.map(
            packageName = "com.whatsapp",
            appName = "WhatsApp",
            eventType = "TYPE_WINDOW_CONTENT_CHANGED",
            screenSummary = summary,
            isTargetApp = true,
            isSupportedEventType = true,
            capturedAt = capturedAt,
        )

        assertEquals("com.whatsapp", capture.packageName)
        assertEquals("WhatsApp", capture.appName)
        assertEquals("TYPE_WINDOW_CONTENT_CHANGED", capture.eventType)
        assertEquals(listOf("first", "second"), capture.screenText)
        assertEquals(true, capture.isTargetApp)
        assertEquals(true, capture.isSupportedEventType)
        assertEquals(1, capture.nodes.size)
        assertEquals("node text", capture.nodes.single().text)
        assertEquals("description", capture.nodes.single().contentDescription)
        assertEquals("android.widget.EditText", capture.nodes.single().className)
        assertEquals("com.whatsapp:id/message", capture.nodes.single().viewIdResourceName)
        assertTrue(capture.nodes.single().isClickable)
        assertTrue(capture.nodes.single().isEditable)
        assertEquals(capturedAt, OffsetDateTime.parse(capture.capturedAtDevice, DateTimeFormatter.ISO_OFFSET_DATE_TIME))
        assertEquals(10_800, OffsetDateTime.parse(capture.capturedAtDevice).offset.totalSeconds)
    }
}
