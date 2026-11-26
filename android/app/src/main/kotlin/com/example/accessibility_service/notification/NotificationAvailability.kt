package com.example.accessibility_service.notification

enum class NotificationAvailability(val wireValue: String) {
    READY("ready"),
    PERMISSION_REQUIRED("permission_required"),
    APP_DISABLED("app_disabled"),
    CHANNEL_DISABLED("channel_disabled"),
    UNAVAILABLE("unavailable"),
}
