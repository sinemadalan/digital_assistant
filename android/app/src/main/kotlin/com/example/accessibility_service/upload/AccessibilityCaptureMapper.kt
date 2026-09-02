package com.example.accessibility_service.upload

import com.example.accessibility_service.Util.ScreenSummary
import com.example.accessibility_service.persistence.QueuedCapture
import com.example.accessibility_service.persistence.QueuedCaptureNode
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

internal object AccessibilityCaptureMapper {
    fun map(
        packageName: String,
        appName: String,
        eventType: String,
        screenSummary: ScreenSummary,
        isTargetApp: Boolean,
        isSupportedEventType: Boolean,
        capturedAt: OffsetDateTime = OffsetDateTime.now(),
    ): QueuedCapture = QueuedCapture(
        packageName = packageName,
        appName = appName,
        eventType = eventType,
        capturedAtDevice = capturedAt.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
        screenText = screenSummary.texts.toList(),
        nodes = screenSummary.nodes.map { node ->
            QueuedCaptureNode(
                text = node.text,
                contentDescription = node.contentDescription,
                className = node.className,
                viewIdResourceName = node.viewIdResourceName,
                isClickable = node.isClickable,
                isEditable = node.isEditable,
            )
        },
        isTargetApp = isTargetApp,
        isSupportedEventType = isSupportedEventType,
    )
}
