package com.example.accessibility_service.Util

/** Raw screen coordinates; offscreen and negative values are preserved. */
data class BoundsInScreen(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
)
