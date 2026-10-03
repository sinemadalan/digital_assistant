package com.example.accessibility_service.Util
data class ScreenSummary(
    val nodeCount: Int = 0,
    val texts: List<String> = emptyList(),
    val nodes: List<CaptureNode> = emptyList(),
    val rootPackageName: String? = null,
    val windowBoundsInScreen: BoundsInScreen? = null,
)
