package com.example.accessibility_service.networking

import java.io.IOException
import java.net.SocketTimeoutException
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NotificationsApiClientTest {
    @Test
    fun parsesServerFieldsAndPreservesUnknownCategoriesAndPlainText() = runTest {
        val row = JSONObject(notificationJson())
            .put("category", "future_category")
            .put("title", "<b>GiDA</b>")
            .put("is_test", true)
            .put("future_field", "ignored")
        val transport = FakeNotificationsTransport(NotificationsHttpResponse(200, envelope(row)))
        val client = NotificationsApiClient(transport, StandardTestDispatcher(testScheduler))

        val result = client.fetchPending("device-token") as NotificationsApiResult.Success
        val notification = result.value.single()
        assertEquals(NOTIFICATION_ID, notification.id)
        assertEquals("future_category", notification.category)
        assertEquals(NotificationSeverity.HIGH, notification.severity)
        assertEquals("<b>GiDA</b>", notification.title)
        assertEquals("Generic notification.", notification.body)
        assertEquals("2026-10-22T09:00:00Z", notification.createdAt)
        assertTrue(notification.isTest)
        assertEquals(listOf(Triple("GET", NotificationsApiClient.PENDING_PATH, "device-token")), transport.calls)
    }

    @Test
    fun parsesEmptyInboxAndAllSeverityValues() = runTest {
        val transport = FakeNotificationsTransport(NotificationsHttpResponse(200, """{"notifications":[]}"""))
        val client = NotificationsApiClient(transport, StandardTestDispatcher(testScheduler))
        assertEquals(NotificationsApiResult.Success(emptyList<ServerNotification>()), client.fetchPending("token"))
        for (severity in NotificationSeverity.entries) {
            transport.response = NotificationsHttpResponse(
                200, envelope(JSONObject(notificationJson()).put("severity", severity.wireValue)),
            )
            val result = client.fetchPending("token") as NotificationsApiResult.Success
            assertEquals(severity, result.value.single().severity)
        }
    }

    @Test
    fun rejectsMalformedEnvelopesAndMistypedOrMissingFields() = runTest {
        val transport = FakeNotificationsTransport()
        val client = NotificationsApiClient(transport, StandardTestDispatcher(testScheduler))
        val invalid = mutableListOf("not json", "{}", """{"notifications":null}""", """{"notifications":{}}""")
        for (field in listOf("id", "category", "severity", "title", "body", "created_at", "is_test")) {
            val row = JSONObject(notificationJson())
            row.remove(field)
            invalid.add(envelope(row))
        }
        for ((field, value) in listOf(
            "id" to "../other/ack",
            "category" to 7,
            "severity" to "unknown",
            "title" to JSONObject.NULL,
            "body" to "",
            "created_at" to "yesterday",
            "is_test" to "false",
        )) {
            invalid.add(envelope(JSONObject(notificationJson()).put(field, value)))
        }
        for (body in invalid) {
            transport.response = NotificationsHttpResponse(200, body)
            assertEquals(body, NotificationsApiResult.InvalidResponse, client.fetchPending("token"))
        }
    }

    @Test
    fun acceptsFullPageAndRejectsOversizedPage() = runTest {
        val transport = FakeNotificationsTransport()
        val client = NotificationsApiClient(transport, StandardTestDispatcher(testScheduler))
        fun page(count: Int) = JSONObject().put(
            "notifications", JSONArray(List(count) { JSONObject(notificationJson()) }),
        ).toString()
        transport.response = NotificationsHttpResponse(200, page(100))
        assertEquals(100, (client.fetchPending("token") as NotificationsApiResult.Success).value.size)
        transport.response = NotificationsHttpResponse(200, page(101))
        assertEquals(NotificationsApiResult.InvalidResponse, client.fetchPending("token"))
    }

    @Test
    fun acknowledgementRequires204AndNeverParsesItsBody() = runTest {
        val transport = FakeNotificationsTransport(NotificationsHttpResponse(204, "not JSON"))
        val client = NotificationsApiClient(transport, StandardTestDispatcher(testScheduler))
        repeat(2) { assertEquals(NotificationsApiResult.Success(Unit), client.acknowledge("token", NOTIFICATION_ID)) }
        assertEquals(Triple("POST", "/v1/notifications/$NOTIFICATION_ID/ack", "token"), transport.calls.first())
        transport.response = NotificationsHttpResponse(200, "{}")
        assertEquals(NotificationsApiResult.InvalidResponse, client.acknowledge("token", NOTIFICATION_ID))
        transport.response = NotificationsHttpResponse(204)
        assertEquals(NotificationsApiResult.InvalidResponse, client.fetchPending("token"))
    }

    @Test
    fun invalidCredentialsOrIdsNeverReachTransport() = runTest {
        val transport = FakeNotificationsTransport()
        val client = NotificationsApiClient(transport, StandardTestDispatcher(testScheduler))
        for (token in listOf(null, "", " ", "token\r\nInjected: value")) {
            assertEquals(
                NotificationsApiResult.InvalidRequest(NotificationRequestError.INVALID_TOKEN),
                client.fetchPending(token),
            )
            assertEquals(
                NotificationsApiResult.InvalidRequest(NotificationRequestError.INVALID_TOKEN),
                client.acknowledge(token, NOTIFICATION_ID),
            )
        }
        for (id in listOf("", "../other", "1-1-1-1-1", "$NOTIFICATION_ID/ack")) {
            assertEquals(
                NotificationsApiResult.InvalidRequest(NotificationRequestError.INVALID_NOTIFICATION_ID),
                client.acknowledge("token", id),
            )
        }
        assertTrue(transport.calls.isEmpty())
    }

    @Test
    fun classifiesHttpErrorsForBothRoutesAndPassesRetryAfter() = runTest {
        val transport = FakeNotificationsTransport()
        val client = NotificationsApiClient(transport, StandardTestDispatcher(testScheduler))
        val expectations = mapOf(
            401 to NotificationsApiResult.Unauthorized,
            404 to NotificationsApiResult.NotFound,
            429 to NotificationsApiResult.RetryableHttpError(429, 12000),
            500 to NotificationsApiResult.RetryableHttpError(500, 12000),
            503 to NotificationsApiResult.RetryableHttpError(503, 12000),
            400 to NotificationsApiResult.ClientHttpError(400),
            403 to NotificationsApiResult.ClientHttpError(403),
            422 to NotificationsApiResult.ClientHttpError(422),
            302 to NotificationsApiResult.UnexpectedHttpError(302),
        )
        for ((status, expected) in expectations) {
            transport.response = NotificationsHttpResponse(status, "sensitive error detail", "12")
            assertEquals(expected, client.fetchPending("token"))
            assertEquals(expected, client.acknowledge("token", NOTIFICATION_ID))
        }
        assertEquals(expectations.size * 2, transport.calls.size) // No hidden retries.
    }

    @Test
    fun distinguishesTimeoutNetworkAndOversizeAndPropagatesCancellation() = runTest {
        val transport = FakeNotificationsTransport()
        val client = NotificationsApiClient(transport, StandardTestDispatcher(testScheduler))
        for ((failure, expected) in listOf(
            SocketTimeoutException() to NotificationsApiResult.Timeout,
            IOException("sensitive transport detail") to NotificationsApiResult.NetworkError,
            NotificationResponseTooLargeException() to NotificationsApiResult.InvalidResponse,
        )) {
            transport.failure = failure
            assertEquals(expected, client.fetchPending("token"))
            assertEquals(expected, client.acknowledge("token", NOTIFICATION_ID))
        }
        transport.failure = CancellationException("cancelled")
        try {
            client.fetchPending("token")
            fail("Cancellation must propagate")
        } catch (_: CancellationException) {
            // Expected.
        }
    }

    @Test
    fun retryAfterSupportsSecondsHttpDatesAndInvalidValues() {
        val now = Instant.parse("2026-10-22T09:00:00Z").toEpochMilli()
        assertEquals(12000L, parseRetryAfter(" 12 ", now))
        assertEquals(0L, parseRetryAfter("0", now))
        assertEquals(30000L, parseRetryAfter("Thu, 22 Oct 2026 09:00:30 GMT", now))
        assertEquals(0L, parseRetryAfter("Thu, 22 Oct 2026 08:59:30 GMT", now))
        for (header in listOf(null, "", "-1", "1.5", "tomorrow", "99999999999999999999999999")) {
            assertNull(parseRetryAfter(header, now))
        }
    }
}

private const val NOTIFICATION_ID = "019a0a45-7000-7000-8000-000000000001"
private fun notificationJson() = """
    {
      "id":"$NOTIFICATION_ID",
      "category":"hakaret",
      "severity":"high",
      "title":"GiDA",
      "body":"Generic notification.",
      "created_at":"2026-10-22T09:00:00Z",
      "is_test":false
    }
""".trimIndent()
private fun envelope(row: JSONObject) = JSONObject().put("notifications", JSONArray().put(row)).toString()

private class FakeNotificationsTransport(
    var response: NotificationsHttpResponse = NotificationsHttpResponse(200, """{"notifications":[]}"""),
) : NotificationsHttpTransport {
    val calls = mutableListOf<Triple<String, String, String>>()
    var failure: Exception? = null
    override fun execute(method: String, path: String, token: String): NotificationsHttpResponse {
        calls.add(Triple(method, path, token))
        failure?.let { throw it }
        return response
    }
}
