package com.example.accessibility_service.networking

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

internal interface NotificationsHttpTransport {
    @Throws(IOException::class)
    fun execute(method: String, path: String, token: String): NotificationsHttpResponse
}

internal data class NotificationsHttpResponse(
    val statusCode: Int,
    val body: String = "",
    val retryAfter: String? = null,
)

internal class UrlConnectionNotificationsTransport(
    private val connectionFactory: (URL) -> HttpURLConnection = {
        it.openConnection() as HttpURLConnection
    },
) : NotificationsHttpTransport {
    override fun execute(method: String, path: String, token: String): NotificationsHttpResponse {
        val endpoint = URL(NotificationsApiClient.BASE_URL + path)
        require(endpoint.protocol == "https")
        val connection = connectionFactory(endpoint)
        try {
            connection.requestMethod = method
            connection.connectTimeout = NotificationsApiClient.CONNECT_TIMEOUT_MS
            connection.readTimeout = NotificationsApiClient.READ_TIMEOUT_MS
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.setRequestProperty("Accept", "application/json")
            // Acknowledgements are bodyless POSTs. Never read or parse their 204 response.
            val status = connection.responseCode
            val retryAfter = connection.getHeaderField("Retry-After")
            val body = if (method == "GET" && status == 200) {
                connection.inputStream.use { input ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0
                    while (true) {
                        val count = input.read(buffer)
                        if (count == -1) break
                        total += count
                        if (total > NotificationsApiClient.MAX_RESPONSE_BYTES) {
                            throw NotificationResponseTooLargeException()
                        }
                        output.write(buffer, 0, count)
                    }
                    output.toString(StandardCharsets.UTF_8.name())
                }
            } else {
                connection.errorStream?.close()
                ""
            }
            return NotificationsHttpResponse(status, body, retryAfter)
        } finally {
            connection.disconnect()
        }
    }
}

internal class NotificationResponseTooLargeException : IOException()
