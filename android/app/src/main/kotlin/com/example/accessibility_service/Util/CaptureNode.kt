package com.example.accessibility_service.Util

data class CaptureNode(
    val text: String? = null,
    val contentDescription: String? = null,
    val className: String? = null,
    val viewIdResourceName: String? = null,
    val isClickable: Boolean = false,
    val isEditable: Boolean = false,
    val visibleToUser: Boolean,
    val boundsInScreen: BoundsInScreen,
    /** Index in the same summary's nodes list; null only for the traversal root. */
    val parentIndex: Int?,
)
