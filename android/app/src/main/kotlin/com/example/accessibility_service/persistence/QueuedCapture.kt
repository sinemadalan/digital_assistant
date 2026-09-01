package com.example.accessibility_service.persistence

data class QueuedCapture(
    val packageName: String,
    val appName: String,
    val eventType: String,
    val capturedAtDevice: String,
    val screenText: List<String>,
    val nodes: List<QueuedCaptureNode>,
    val isTargetApp: Boolean,
    val isSupportedEventType: Boolean,
)

data class QueuedCaptureNode(
    val text: String? = null,
    val contentDescription: String? = null,
    val className: String? = null,
    val viewIdResourceName: String? = null,
    val isClickable: Boolean = false,
    val isEditable: Boolean = false,
)
