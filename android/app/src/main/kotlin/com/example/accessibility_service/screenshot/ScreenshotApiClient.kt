package com.example.accessibility_service.screenshot

import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

sealed interface ScreenshotUploadResult {
    data object Success : ScreenshotUploadResult
    data object Unauthorized : ScreenshotUploadResult
    data object NotFound : ScreenshotUploadResult
    data object Timeout : ScreenshotUploadResult
    data class NetworkError(val error: IOException) : ScreenshotUploadResult
    data class ServerError(val statusCode: Int) : ScreenshotUploadResult
    data class OtherHttpError(val statusCode: Int) : ScreenshotUploadResult
}

interface ScreenshotApiClient {
    suspend fun uploadScreenshot(token: String, screenshot: QueuedScreenshot): ScreenshotUploadResult
}

class MultipartScreenshotApiClient(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val baseUrl: String = "https://api.152-70-40-87.nip.io",
    private val endpointPath: String = "/v1/captures/screenshot",
    private val connectionFactory: (URL) -> HttpURLConnection = { url ->
        url.openConnection() as HttpURLConnection
    }
) : ScreenshotApiClient {

    override suspend fun uploadScreenshot(token: String, screenshot: QueuedScreenshot): ScreenshotUploadResult {
        return withContext(ioDispatcher) {
            val url = URL(baseUrl + endpointPath)
            var connection: HttpURLConnection? = null
            try {
                connection = connectionFactory(url)
                val boundary = "Boundary-${UUID.randomUUID()}"
                
                connection.requestMethod = "POST"
                connection.connectTimeout = 15_000
                connection.readTimeout = 30_000
                connection.doOutput = true
                connection.setRequestProperty("Authorization", "Bearer $token")
                connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")

                connection.outputStream.use { output ->
                    // Add package name part
                    writeFormField(output, boundary, "packageName", screenshot.packageName)
                    
                    // Add file part
                    writeFileField(output, boundary, "screenshot", screenshot.file.name, "image/jpeg", screenshot.file)
                    
                    // End boundary
                    output.write("--$boundary--\r\n".toByteArray())
                    output.flush()
                }

                val statusCode = connection.responseCode
                when (statusCode) {
                    in 200..299 -> ScreenshotUploadResult.Success
                    HttpURLConnection.HTTP_UNAUTHORIZED -> ScreenshotUploadResult.Unauthorized
                    HttpURLConnection.HTTP_NOT_FOUND -> ScreenshotUploadResult.NotFound
                    in 500..599 -> ScreenshotUploadResult.ServerError(statusCode)
                    else -> ScreenshotUploadResult.OtherHttpError(statusCode)
                }
            } catch (e: SocketTimeoutException) {
                ScreenshotUploadResult.Timeout
            } catch (e: IOException) {
                ScreenshotUploadResult.NetworkError(e)
            } finally {
                connection?.disconnect()
            }
        }
    }

    private fun writeFormField(output: OutputStream, boundary: String, fieldName: String, value: String) {
        val part = "--$boundary\r\n" +
                "Content-Disposition: form-data; name=\"$fieldName\"\r\n\r\n" +
                "$value\r\n"
        output.write(part.toByteArray())
    }

    private fun writeFileField(output: OutputStream, boundary: String, fieldName: String, fileName: String, contentType: String, file: File) {
        val partHeader = "--$boundary\r\n" +
                "Content-Disposition: form-data; name=\"$fieldName\"; filename=\"$fileName\"\r\n" +
                "Content-Type: $contentType\r\n\r\n"
        output.write(partHeader.toByteArray())
        
        FileInputStream(file).use { input ->
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (input.read(buffer).also { bytesRead = it } != -1) {
                output.write(buffer, 0, bytesRead)
            }
        }
        output.write("\r\n".toByteArray())
    }
}
