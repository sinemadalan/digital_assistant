package com.example.accessibility_service.networking

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine

internal sealed interface NotificationStreamOpenResult {
    data class Connected(val session: NotificationStreamSession) : NotificationStreamOpenResult
    data class Failed(val error: NotificationsApiResult<Nothing>) : NotificationStreamOpenResult
}

internal interface NotificationStreamSession {
    suspend fun consume(onEvent: (SseEvent) -> Unit)
    fun close()
}

internal interface NotificationStreamTransport {
    suspend fun open(token: String): NotificationStreamOpenResult
}

internal data class SseEvent(val name: String, val data: String)

internal class UrlConnectionNotificationStreamTransport(
    private val connectionFactory: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : NotificationStreamTransport {
    override suspend fun open(token: String): NotificationStreamOpenResult {
        if (!isValidNotificationToken(token)) {
            return NotificationStreamOpenResult.Failed(
                NotificationsApiResult.InvalidRequest(NotificationRequestError.INVALID_TOKEN),
            )
        }
        val endpoint = URL(NotificationsApiClient.BASE_URL + STREAM_PATH)
        require(endpoint.protocol == "https")
        return suspendCancellableCoroutine { continuation ->
            val connection = try {
                connectionFactory(endpoint)
            } catch (_: IOException) {
                continuation.resume(NotificationStreamOpenResult.Failed(NotificationsApiResult.NetworkError))
                return@suspendCancellableCoroutine
            }
            val worker = CoroutineScope(Dispatchers.IO).launch {
                val result = try {
                    connection.requestMethod = "GET"
                    connection.connectTimeout = NotificationsApiClient.CONNECT_TIMEOUT_MS
                    connection.readTimeout = STREAM_READ_TIMEOUT_MS
                    connection.instanceFollowRedirects = false
                    connection.setRequestProperty("Authorization", "Bearer $token")
                    connection.setRequestProperty("Accept", "text/event-stream")
                    val status = connection.responseCode
                    if (status == 200 && connection.contentType?.substringBefore(';')?.trim()
                            ?.equals("text/event-stream", ignoreCase = true) == true) {
                        NotificationStreamOpenResult.Connected(UrlConnectionStreamSession(connection))
                    } else {
                        val error = if (status == 200) NotificationsApiResult.InvalidResponse
                            else classifyNotificationHttpError(status, connection.getHeaderField("Retry-After"), nowMillis())
                        try { connection.errorStream?.close() } catch (_: IOException) { /* Status wins. */ }
                        connection.disconnect()
                        NotificationStreamOpenResult.Failed(error)
                    }
                } catch (_: SocketTimeoutException) {
                    connection.disconnect()
                    NotificationStreamOpenResult.Failed(NotificationsApiResult.Timeout)
                } catch (_: IOException) {
                    connection.disconnect()
                    NotificationStreamOpenResult.Failed(NotificationsApiResult.NetworkError)
                }
                if (continuation.isActive) continuation.resume(result)
                else if (result is NotificationStreamOpenResult.Connected) result.session.close()
            }
            continuation.invokeOnCancellation { connection.disconnect(); worker.cancel() }
        }
    }

    internal companion object {
        const val STREAM_PATH = "/v1/notifications/stream"
        const val STREAM_READ_TIMEOUT_MS = 50_000
    }
}

private class UrlConnectionStreamSession(private val connection: HttpURLConnection) : NotificationStreamSession {
    private val closed = AtomicBoolean(false)

    override suspend fun consume(onEvent: (SseEvent) -> Unit) {
        suspendCancellableCoroutine<Unit> { continuation ->
            val worker: Job = CoroutineScope(Dispatchers.IO).launch {
                try {
                    connection.inputStream.use { input ->
                        val parser = SseParser(input)
                        while (!closed.get()) {
                            val event = parser.nextEvent() ?: break
                            if (continuation.isActive) onEvent(event)
                        }
                    }
                    if (continuation.isActive) continuation.resume(Unit)
                } catch (error: IOException) {
                    if (continuation.isActive) continuation.resumeWith(Result.failure(error))
                } catch (error: RuntimeException) {
                    if (continuation.isActive) continuation.resumeWith(Result.failure(error))
                } finally {
                    close()
                }
            }
            continuation.invokeOnCancellation { close(); worker.cancel() }
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) connection.disconnect()
    }
}

/** Reads bounded UTF-8 lines; EOF does not dispatch an incomplete event. */
internal class SseParser(input: InputStream) {
    private val source = BufferedInputStream(input)

    fun nextEvent(): SseEvent? {
        var name = "message"
        val data = StringBuilder()
        var bytes = 0
        while (true) {
            val line = readLine() ?: return null
            bytes += line.size + 1
            if (bytes > MAX_EVENT_BYTES) throw IOException("SSE event too large")
            if (line.isEmpty()) {
                if (data.isNotEmpty()) return SseEvent(name, data.substring(0, data.length - 1))
                name = "message"
                bytes = 0
                continue
            }
            val decoded = line.toString(Charsets.UTF_8)
            if (decoded.startsWith(':')) continue
            val colon = decoded.indexOf(':')
            val field = if (colon == -1) decoded else decoded.substring(0, colon)
            val value = if (colon == -1) "" else decoded.substring(colon + 1).removePrefix(" ")
            when (field) {
                "event" -> name = value
                "data" -> data.append(value).append('\n')
            }
        }
    }

    private fun readLine(): ByteArray? {
        val output = ByteArrayOutputStream()
        while (true) {
            val byte = source.read()
            if (byte == -1) return if (output.size() == 0) null else output.toByteArray()
            if (byte == '\n'.code) {
                val line = output.toByteArray()
                return if (line.lastOrNull() == '\r'.code.toByte()) line.copyOf(line.size - 1) else line
            }
            if (output.size() >= MAX_LINE_BYTES) throw IOException("SSE line too large")
            output.write(byte)
        }
    }

    internal companion object {
        const val MAX_LINE_BYTES = 8 * 1024
        const val MAX_EVENT_BYTES = 64 * 1024
    }
}
