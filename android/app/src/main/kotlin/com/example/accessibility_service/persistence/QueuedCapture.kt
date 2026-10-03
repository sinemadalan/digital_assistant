package com.example.accessibility_service.persistence

import com.example.accessibility_service.Util.BoundsInScreen

data class QueuedCapture(
    val packageName: String,
    val appName: String,
    val eventType: String,
    val capturedAtDevice: String,
    val screenText: List<String>,
    val nodes: List<QueuedCaptureNode>,
    val isTargetApp: Boolean,
    val isSupportedEventType: Boolean,
    val rootPackageName: String? = null,
    val windowBoundsInScreen: BoundsInScreen? = null,
)

data class QueuedCaptureNode(
    val text: String? = null,
    val contentDescription: String? = null,
    val className: String? = null,
    val viewIdResourceName: String? = null,
    val isClickable: Boolean = false,
    val isEditable: Boolean = false,
    val visibleToUser: Boolean,
    val boundsInScreen: BoundsInScreen,
    /** Index in the enclosing capture's nodes list; null means no parent. */
    val parentIndex: Int?,
)

internal fun QueuedCapture.validateParentIndices() {
    require(nodes.all { it.parentIndex == null || it.parentIndex in nodes.indices }) {
        "Capture contains an out-of-range parentIndex."
    }
}
