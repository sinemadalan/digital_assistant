package com.example.accessibility_service.networking

/**
 * Device inbox payload, separate from the Android display model AppNotification.
 * Keep the server UUID intact for persistence, deduplication and acknowledgements.
 */
data class ServerNotification(
    val id: String,
    val category: String,
    val severity: NotificationSeverity,
    val title: String,
    val body: String,
    val createdAt: String,
    val isTest: Boolean,
)

enum class NotificationSeverity(val wireValue: String) {
    LOW("low"),
    MEDIUM("medium"),
    HIGH("high"),
    CRITICAL("critical"),
}

sealed interface NotificationsApiResult<out T> {
    data class Success<T>(val value: T) : NotificationsApiResult<T>
    data object Unauthorized : NotificationsApiResult<Nothing>
    data object NotFound : NotificationsApiResult<Nothing>
    data class RetryableHttpError(
        val statusCode: Int,
        val retryAfterMillis: Long?,
    ) : NotificationsApiResult<Nothing>
    data class ClientHttpError(val statusCode: Int) : NotificationsApiResult<Nothing>
    data class UnexpectedHttpError(val statusCode: Int) : NotificationsApiResult<Nothing>
    data object Timeout : NotificationsApiResult<Nothing>
    data object NetworkError : NotificationsApiResult<Nothing>
    data object InvalidResponse : NotificationsApiResult<Nothing>
    data class InvalidRequest(val reason: NotificationRequestError) : NotificationsApiResult<Nothing>
}

enum class NotificationRequestError {
    INVALID_TOKEN,
    INVALID_NOTIFICATION_ID,
}
