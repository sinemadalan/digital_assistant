package com.example.accessibility_service.networking

import android.util.Log
import com.example.accessibility_service.persistence.QueuedCapture
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class CapturesApiClient internal constructor(
    private val transport: CapturesHttpTransport,
    private val ioDispatcher: CoroutineDispatcher,
    private val pipelineLogger: (String) -> Unit = {},
) {
    constructor() : this(
        UrlConnectionCapturesTransport(),
        Dispatchers.IO,
        { message -> Log.i(PHASE5A_TAG, message) },
    )

    suspend fun sendCaptures(
        token: String?,
        captures: List<QueuedCapture>,
    ): CapturesApiResult {
        validate(token, captures)?.let { return CapturesApiResult.InvalidRequest(it) }
        val nonBlankToken = requireNotNull(token)
        val requestBody = CapturesJsonSerializer.serialize(captures)

        return withContext(ioDispatcher) {
            val response = try {
                pipelineLogger("Phase5A: POST $ENDPOINT_PATH, events=${captures.size}")
                transport.post(nonBlankToken, requestBody)
            } catch (_: SocketTimeoutException) {
                return@withContext CapturesApiResult.Timeout
            } catch (error: ResponseTooLargeException) {
                return@withContext CapturesApiResult.InvalidResponse(error.message.orEmpty())
            } catch (error: IOException) {
                return@withContext CapturesApiResult.NetworkError(error)
            }
            pipelineLogger("Phase5A: $ENDPOINT_PATH response status=${response.statusCode}")

            when {
                response.statusCode in 200..299 -> parseSuccess(response.body, captures.size)
                response.statusCode == HttpURLConnection.HTTP_UNAUTHORIZED -> CapturesApiResult.Unauthorized
                response.statusCode == HTTP_UNPROCESSABLE_ENTITY -> CapturesApiResult.Unprocessable
                response.statusCode == HttpURLConnection.HTTP_UNAVAILABLE -> CapturesApiResult.ServiceUnavailable
                response.statusCode in 500..599 ->
                    CapturesApiResult.RetryableServerError(response.statusCode)
                else -> CapturesApiResult.OtherHttpError(response.statusCode)
            }
        }
    }

    private fun validate(token: String?, captures: List<QueuedCapture>): InvalidRequestReason? = when {
        token.isNullOrBlank() -> InvalidRequestReason.BLANK_TOKEN
        captures.isEmpty() -> InvalidRequestReason.EMPTY_EVENTS
        captures.size > MAX_EVENTS_PER_REQUEST -> InvalidRequestReason.TOO_MANY_EVENTS
        else -> null
    }

    private fun parseSuccess(body: String, sentEventCount: Int): CapturesApiResult = try {
        CapturesApiResult.Success(CapturesResponseParser.parse(body, sentEventCount))
    } catch (error: InvalidCapturesResponseException) {
        CapturesApiResult.InvalidResponse(error.message ?: "Invalid captures response")
    } catch (error: JSONExceptionCompat) {
        CapturesApiResult.InvalidResponse(error.message ?: "Invalid captures response")
    } catch (error: RuntimeException) {
        CapturesApiResult.InvalidResponse("Invalid captures response")
    }

    companion object {
        private const val PHASE5A_TAG = "Phase5A"
        const val BASE_URL = "https://api.152-70-40-87.nip.io"
        const val ENDPOINT_PATH = "/v1/captures"
        const val MAX_EVENTS_PER_REQUEST = 50
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 30_000
        const val MAX_RESPONSE_BYTES = 256 * 1024
        private const val HTTP_UNPROCESSABLE_ENTITY = 422
    }
}

internal interface CapturesHttpTransport {
    @Throws(IOException::class, ResponseTooLargeException::class)
    fun post(token: String, utf8JsonBody: String): CapturesHttpResponse
}

internal data class CapturesHttpResponse(
    val statusCode: Int,
    val body: String,
)

internal class UrlConnectionCapturesTransport(
    private val connectionFactory: (URL) -> HttpURLConnection = { url ->
        url.openConnection() as HttpURLConnection
    },
) : CapturesHttpTransport {
    private val endpoint = URL(CapturesApiClient.BASE_URL + CapturesApiClient.ENDPOINT_PATH).also {
        require(it.protocol == "https") { "Captures endpoint must use HTTPS" }
    }

    override fun post(token: String, utf8JsonBody: String): CapturesHttpResponse {
        val connection = connectionFactory(endpoint)
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = CapturesApiClient.CONNECT_TIMEOUT_MS
            connection.readTimeout = CapturesApiClient.READ_TIMEOUT_MS
            connection.instanceFollowRedirects = false
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Authorization", "Bearer $token")

            connection.outputStream.use { output ->
                output.write(utf8JsonBody.toByteArray(StandardCharsets.UTF_8))
                output.flush()
            }

            val statusCode = connection.responseCode
            val body = if (statusCode in 200..299) {
                connection.inputStream.use { input ->
                    input.readLimited(
                        limit = CapturesApiClient.MAX_RESPONSE_BYTES,
                        charset = responseCharset(connection.contentType),
                    )
                }
            } else {
                connection.errorStream?.use { /* Close without retaining potentially sensitive content. */ }
                ""
            }
            return CapturesHttpResponse(statusCode, body)
        } finally {
            connection.disconnect()
        }
    }

    private fun responseCharset(contentType: String?): Charset {
        val charsetName = contentType
            ?.split(';')
            ?.drop(1)
            ?.map(String::trim)
            ?.firstOrNull { it.startsWith("charset=", ignoreCase = true) }
            ?.substringAfter('=')
            ?.trim()
            ?.trim('"')
            ?: return StandardCharsets.UTF_8
        return try {
            Charset.forName(charsetName)
        } catch (_: Exception) {
            StandardCharsets.UTF_8
        }
    }

    private fun InputStream.readLimited(limit: Int, charset: Charset): String {
        val output = ByteArrayOutputStream(minOf(limit, DEFAULT_BUFFER_SIZE))
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0
        while (true) {
            val count = read(buffer)
            if (count == -1) break
            total += count
            if (total > limit) {
                throw ResponseTooLargeException("Captures response exceeds $limit bytes")
            }
            output.write(buffer, 0, count)
        }
        return output.toString(charset.name())
    }
}

internal class ResponseTooLargeException(message: String) : IOException(message)

// Keeps malformed org.json runtime failures behind the protocol-error boundary on all Android API levels.
private typealias JSONExceptionCompat = org.json.JSONException
