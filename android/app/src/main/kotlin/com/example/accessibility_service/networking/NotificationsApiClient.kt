package com.example.accessibility_service.networking

import java.io.IOException
import java.net.SocketTimeoutException
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * One request per call; never automatically retries a consuming pending fetch.
 * The caller supplies the current device JWT from NativeTokenStore.
 * Persistence, retry scheduling and Android presentation belong to the coordinator.
 */
class NotificationsApiClient internal constructor(
    private val transport: NotificationsHttpTransport,
    private val ioDispatcher: CoroutineDispatcher,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    constructor() : this(UrlConnectionNotificationsTransport(), Dispatchers.IO)

    suspend fun fetchPending(token: String?): NotificationsApiResult<List<ServerNotification>> =
        request(token, "GET", PENDING_PATH, 200) { response ->
            NotificationsResponseParser.parse(response.body)
        }

    suspend fun acknowledge(token: String?, notificationId: String): NotificationsApiResult<Unit> {
        if (!isCanonicalNotificationId(notificationId)) {
            return NotificationsApiResult.InvalidRequest(NotificationRequestError.INVALID_NOTIFICATION_ID)
        }
        return request(token, "POST", "/v1/notifications/$notificationId/ack", 204) { Unit }
    }

    private suspend fun <T> request(
        token: String?,
        method: String,
        path: String,
        successStatus: Int,
        parse: (NotificationsHttpResponse) -> T,
    ): NotificationsApiResult<T> {
        if (token.isNullOrBlank() || token.any { it == '\r' || it == '\n' }) {
            return NotificationsApiResult.InvalidRequest(NotificationRequestError.INVALID_TOKEN)
        }
        return withContext(ioDispatcher) {
            val response = try {
                transport.execute(method, path, token)
            } catch (_: SocketTimeoutException) {
                return@withContext NotificationsApiResult.Timeout
            } catch (_: NotificationResponseTooLargeException) {
                return@withContext NotificationsApiResult.InvalidResponse
            } catch (_: IOException) {
                return@withContext NotificationsApiResult.NetworkError
            }
            when {
                response.statusCode == successStatus -> try {
                    NotificationsApiResult.Success(parse(response))
                } catch (_: IllegalArgumentException) {
                    NotificationsApiResult.InvalidResponse
                } catch (_: org.json.JSONException) {
                    NotificationsApiResult.InvalidResponse
                } catch (_: java.time.format.DateTimeParseException) {
                    NotificationsApiResult.InvalidResponse
                }
                response.statusCode == 401 -> NotificationsApiResult.Unauthorized
                response.statusCode == 404 -> NotificationsApiResult.NotFound
                response.statusCode == 429 || response.statusCode in 500..599 ->
                    NotificationsApiResult.RetryableHttpError(
                        response.statusCode,
                        parseRetryAfter(response.retryAfter, nowMillis()),
                    )
                response.statusCode in 400..499 -> NotificationsApiResult.ClientHttpError(response.statusCode)
                response.statusCode in 200..299 -> NotificationsApiResult.InvalidResponse
                else -> NotificationsApiResult.UnexpectedHttpError(response.statusCode)
            }
        }
    }

    companion object {
        const val BASE_URL = "https://api.152-70-40-87.nip.io"
        const val PENDING_PATH = "/v1/notifications/pending"
        const val MAX_PAGE_SIZE = 100
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 30_000
        const val MAX_RESPONSE_BYTES = 1024 * 1024
    }
}

internal fun isCanonicalNotificationId(value: String): Boolean =
    value.length == 36 && try {
        UUID.fromString(value).toString().equals(value, ignoreCase = true)
    } catch (_: IllegalArgumentException) {
        false
    }

internal object NotificationsResponseParser {
    fun parse(body: String): List<ServerNotification> {
        val rows = JSONObject(body).getJSONArray("notifications")
        require(rows.length() <= NotificationsApiClient.MAX_PAGE_SIZE)
        return List(rows.length()) { index ->
            val row = rows.getJSONObject(index)
            val id = row.requiredString("id").also { require(isCanonicalNotificationId(it)) }
            val severity = row.requiredString("severity")
            val createdAt = row.requiredString("created_at")
            OffsetDateTime.parse(createdAt)
            val isTest = row.get("is_test")
            require(isTest is Boolean)
            ServerNotification(
                id = id,
                category = row.requiredString("category"),
                severity = requireNotNull(NotificationSeverity.entries.find { it.wireValue == severity }),
                title = row.requiredString("title"),
                body = row.requiredString("body"),
                createdAt = createdAt,
                isTest = isTest,
            )
        }
    }

    private fun JSONObject.requiredString(key: String): String {
        val value = get(key)
        require(value is String && value.isNotBlank())
        return value
    }
}

internal fun parseRetryAfter(header: String?, nowMillis: Long): Long? {
    val value = header?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    if (value.all { it in '0'..'9' }) {
        val seconds = value.toLongOrNull() ?: return null
        return seconds.takeIf { it <= Long.MAX_VALUE / 1000 }?.times(1000)
    }
    return try {
        val deadline = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME)
            .toInstant().toEpochMilli()
        Math.subtractExact(deadline, nowMillis).coerceAtLeast(0)
    } catch (_: java.time.DateTimeException) {
        null
    } catch (_: ArithmeticException) {
        null
    }
}
