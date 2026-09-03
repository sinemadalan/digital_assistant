package com.example.accessibility_service.networking

import java.io.IOException
import java.net.SocketTimeoutException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CapturesApiClientTest {
    @Test
    fun validationRejectsBlankTokenEmptyEventsAndMoreThanFiftyWithoutNetwork() = runTest {
        val transport = FakeTransport()
        val client = client(transport)

        assertEquals(InvalidRequestReason.BLANK_TOKEN, client.sendCaptures(null, listOf(sampleCapture())).invalidReason())
        assertEquals(InvalidRequestReason.BLANK_TOKEN, client.sendCaptures("  ", listOf(sampleCapture())).invalidReason())
        assertEquals(InvalidRequestReason.EMPTY_EVENTS, client.sendCaptures("token", emptyList()).invalidReason())
        assertEquals(
            InvalidRequestReason.TOO_MANY_EVENTS,
            client.sendCaptures("token", List(51) { sampleCapture(it) }).invalidReason(),
        )
        assertFalse(transport.called)
    }

    @Test
    fun sendsBearerTokenAndSerializedBodyAndReturnsSuccess() = runTest {
        val transport = FakeTransport(CapturesHttpResponse(201, validResponse()))
        val result = client(transport).sendCaptures("secret-token", listOf(sampleCapture()))

        assertTrue(result is CapturesApiResult.Success)
        assertEquals("secret-token", transport.token)
        assertTrue(transport.body.contains("\"events\""))
    }

    @Test
    fun classifiesKnownAndOtherHttpStatuses() = runTest {
        assertTrue(resultFor(401) is CapturesApiResult.Unauthorized)
        assertTrue(resultFor(422) is CapturesApiResult.Unprocessable)
        assertTrue(resultFor(503) is CapturesApiResult.ServiceUnavailable)
        assertEquals(CapturesApiResult.RetryableServerError(500), resultFor(500))
        assertEquals(CapturesApiResult.RetryableServerError(599), resultFor(599))
        assertEquals(CapturesApiResult.OtherHttpError(302), resultFor(302))
    }

    @Test
    fun classifiesTimeoutSeparatelyFromOtherNetworkErrors() = runTest {
        assertTrue(client(ThrowingTransport(SocketTimeoutException())).sendCaptures("token", listOf(sampleCapture())) is CapturesApiResult.Timeout)
        val failure = IOException("connection failed")
        val result = client(ThrowingTransport(failure)).sendCaptures("token", listOf(sampleCapture()))

        assertTrue(result is CapturesApiResult.NetworkError)
        assertEquals(failure, (result as CapturesApiResult.NetworkError).cause)
    }

    @Test
    fun classifiesMalformedOrPartiallyAccountedTwoHundredBodyAsInvalidResponse() = runTest {
        assertTrue(
            client(FakeTransport(CapturesHttpResponse(200, "<html>ok</html>")))
                .sendCaptures("token", listOf(sampleCapture())) is CapturesApiResult.InvalidResponse,
        )
        assertTrue(
            client(FakeTransport(CapturesHttpResponse(200, validResponse(20, 2))))
                .sendCaptures("token", List(30) { sampleCapture(it) }) is CapturesApiResult.InvalidResponse,
        )
    }

    @Test
    fun classifiesOversizedResponseAsInvalidResponse() = runTest {
        assertTrue(
            client(ThrowingTransport(ResponseTooLargeException("too large")))
                .sendCaptures("token", listOf(sampleCapture())) is CapturesApiResult.InvalidResponse,
        )
    }

    private fun client(transport: CapturesHttpTransport) = CapturesApiClient(
        transport = transport,
        ioDispatcher = Dispatchers.Unconfined,
    )

    private suspend fun resultFor(statusCode: Int): CapturesApiResult =
        client(FakeTransport(CapturesHttpResponse(statusCode, "ignored")))
            .sendCaptures("token", listOf(sampleCapture()))

    private fun CapturesApiResult.invalidReason(): InvalidRequestReason =
        (this as CapturesApiResult.InvalidRequest).reason
}

private class FakeTransport(
    private val response: CapturesHttpResponse = CapturesHttpResponse(200, validResponse()),
) : CapturesHttpTransport {
    var called = false
    var token = ""
    var body = ""

    override fun post(token: String, utf8JsonBody: String): CapturesHttpResponse {
        called = true
        this.token = token
        body = utf8JsonBody
        return response
    }
}

private class ThrowingTransport(private val error: IOException) : CapturesHttpTransport {
    override fun post(token: String, utf8JsonBody: String): CapturesHttpResponse = throw error
}
