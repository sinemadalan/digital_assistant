package com.example.accessibility_service.screenshot

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

class ScreenshotApiClientTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun successfulUploadSendsCorrectMultipartData() = runTest {
        val connection = RecordingConnection(200)
        val client = MultipartScreenshotApiClient(
            ioDispatcher = Dispatchers.Unconfined,
            connectionFactory = { connection }
        )

        val testFile = tempFolder.newFile("test_screenshot.jpg").apply {
            writeBytes("fake-jpeg-data".toByteArray())
        }
        val queuedScreenshot = QueuedScreenshot("id_123", testFile, "com.example.app")

        val result = client.uploadScreenshot("secret-token", queuedScreenshot)

        assertEquals(ScreenshotUploadResult.Success, result)
        assertEquals("POST", connection.requestMethod)
        assertEquals("Bearer secret-token", connection.getRequestProperty("Authorization"))
        assertTrue(connection.getRequestProperty("Content-Type").startsWith("multipart/form-data; boundary=Boundary-"))
        
        val writtenBody = connection.written.toString(StandardCharsets.UTF_8.name())
        assertTrue(writtenBody.contains("name=\"packageName\""))
        assertTrue(writtenBody.contains("com.example.app"))
        assertTrue(writtenBody.contains("name=\"screenshot\"; filename=\"test_screenshot.jpg\""))
        assertTrue(writtenBody.contains("Content-Type: image/jpeg"))
        assertTrue(writtenBody.contains("fake-jpeg-data"))
        
        assertTrue(connection.outputClosed)
        assertTrue(connection.disconnected)
    }

    @Test
    fun unauthorizedReturnsUnauthorizedResult() = runTest {
        val connection = RecordingConnection(401)
        val client = MultipartScreenshotApiClient(
            ioDispatcher = Dispatchers.Unconfined,
            connectionFactory = { connection }
        )
        val testFile = tempFolder.newFile("test_screenshot.jpg")
        
        val result = client.uploadScreenshot("token", QueuedScreenshot("id1", testFile, "pkg"))
        
        assertEquals(ScreenshotUploadResult.Unauthorized, result)
        assertTrue(connection.disconnected)
    }

    @Test
    fun notFoundReturnsNotFoundResult() = runTest {
        val connection = RecordingConnection(404)
        val client = MultipartScreenshotApiClient(
            ioDispatcher = Dispatchers.Unconfined,
            connectionFactory = { connection }
        )
        val testFile = tempFolder.newFile("test_screenshot.jpg")
        
        val result = client.uploadScreenshot("token", QueuedScreenshot("id1", testFile, "pkg"))
        
        assertEquals(ScreenshotUploadResult.NotFound, result)
        assertTrue(connection.disconnected)
    }

    @Test
    fun serverErrorReturnsServerErrorResult() = runTest {
        val connection = RecordingConnection(502)
        val client = MultipartScreenshotApiClient(
            ioDispatcher = Dispatchers.Unconfined,
            connectionFactory = { connection }
        )
        val testFile = tempFolder.newFile("test_screenshot.jpg")
        
        val result = client.uploadScreenshot("token", QueuedScreenshot("id1", testFile, "pkg"))
        
        assertEquals(ScreenshotUploadResult.ServerError(502), result)
        assertTrue(connection.disconnected)
    }

    @Test
    fun otherHttpErrorReturnsOtherHttpErrorResult() = runTest {
        val connection = RecordingConnection(400)
        val client = MultipartScreenshotApiClient(
            ioDispatcher = Dispatchers.Unconfined,
            connectionFactory = { connection }
        )
        val testFile = tempFolder.newFile("test_screenshot.jpg")
        
        val result = client.uploadScreenshot("token", QueuedScreenshot("id1", testFile, "pkg"))
        
        assertEquals(ScreenshotUploadResult.OtherHttpError(400), result)
        assertTrue(connection.disconnected)
    }
}

private class RecordingConnection(
    private val code: Int
) : HttpURLConnection(URL("https://example.invalid/v1/captures/screenshot")) {
    val written = ByteArrayOutputStream()
    var outputClosed = false
    var disconnected = false

    override fun connect() = Unit

    override fun disconnect() {
        disconnected = true
    }

    override fun usingProxy(): Boolean = false

    override fun getResponseCode(): Int = code

    override fun getInputStream(): InputStream = ByteArrayInputStream(ByteArray(0))

    override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))

    override fun getOutputStream(): OutputStream = object : OutputStream() {
        override fun write(value: Int) {
            written.write(value)
        }

        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            written.write(bytes, offset, length)
        }

        override fun close() {
            outputClosed = true
        }
    }
}
