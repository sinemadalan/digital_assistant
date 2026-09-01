package com.example.accessibility_service.networking

import com.example.accessibility_service.persistence.QueuedCapture
import com.example.accessibility_service.persistence.QueuedCaptureNode

internal fun sampleCapture(
    index: Int = 0,
    screenText: List<String> = listOf("Uninstall", "Cancel", "Instagram"),
    nodes: List<QueuedCaptureNode> = listOf(
        QueuedCaptureNode(
            text = "Uninstall",
            contentDescription = null,
            className = "android.widget.Button",
            viewIdResourceName = "com.example:id/action",
            isClickable = true,
            isEditable = false,
        ),
    ),
): QueuedCapture = QueuedCapture(
    packageName = "com.example.app$index",
    appName = "Example $index",
    eventType = "TYPE_WINDOW_CONTENT_CHANGED",
    capturedAtDevice = "2026-09-01T12:00:00+03:00",
    screenText = screenText,
    nodes = nodes,
    isTargetApp = true,
    isSupportedEventType = true,
)

internal fun validResponse(
    accepted: Int = 1,
    skipped: Int = 0,
    commands: String = "[]",
): String = """
    {
      "accepted": $accepted,
      "skipped": $skipped,
      "config": {"batch_size": 30, "flush_seconds": 20},
      "commands": $commands
    }
""".trimIndent()
