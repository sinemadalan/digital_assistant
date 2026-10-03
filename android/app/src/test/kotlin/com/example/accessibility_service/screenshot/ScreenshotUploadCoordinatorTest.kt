package com.example.accessibility_service.screenshot

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.example.accessibility_service.EncryptedToken
import com.example.accessibility_service.NativeTokenStore
import com.example.accessibility_service.TokenCipher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException

class ScreenshotUploadCoordinatorTest {

    @Test
    fun noTokenReturnsWithoutUploading() = runTest {
        val store = newStore()
        val queue = FakeScreenshotQueue(QueuedScreenshot("id1", File("test.jpg"), "pkg"))
        val client = FakeScreenshotApiClient(ScreenshotUploadResult.Success)
        val coordinator = ScreenshotUploadCoordinator(store, queue, client) { }

        coordinator.requestUpload()

        assertEquals(0, client.uploadCalls)
        assertEquals(0, queue.acknowledgeCalls)
    }

    @Test
    fun successfulUploadAcknowledgesScreenshot() = runTest {
        val store = newStore()
        store.installFreshToken("valid-token")
        val queue = FakeScreenshotQueue(QueuedScreenshot("id1", File("test.jpg"), "pkg"))
        val client = FakeScreenshotApiClient(ScreenshotUploadResult.Success)
        val coordinator = ScreenshotUploadCoordinator(store, queue, client) { }

        coordinator.requestUpload()

        assertEquals(1, client.uploadCalls)
        assertEquals(1, queue.acknowledgeCalls)
        assertEquals("id1", queue.lastAcknowledgedId)
    }

    @Test
    fun unauthorizedRevokesTokenAndHalts() = runTest {
        val store = newStore()
        store.installFreshToken("invalid-token")
        val queue = FakeScreenshotQueue(QueuedScreenshot("id1", File("test.jpg"), "pkg"))
        val client = FakeScreenshotApiClient(ScreenshotUploadResult.Unauthorized)
        val coordinator = ScreenshotUploadCoordinator(store, queue, client) { }

        coordinator.requestUpload()

        assertEquals(1, client.uploadCalls)
        assertEquals(0, queue.acknowledgeCalls)
        assertTrue(store.isReauthenticationRequired())
        assertNull(store.getToken())
    }

    @Test
    fun notFoundHaltsWithoutAcknowledging() = runTest {
        val store = newStore()
        store.installFreshToken("valid-token")
        val queue = FakeScreenshotQueue(QueuedScreenshot("id1", File("test.jpg"), "pkg"))
        val client = FakeScreenshotApiClient(ScreenshotUploadResult.NotFound)
        val coordinator = ScreenshotUploadCoordinator(store, queue, client) { }

        coordinator.requestUpload()

        assertEquals(1, client.uploadCalls)
        assertEquals(0, queue.acknowledgeCalls)
    }

    @Test
    fun otherHttpErrorDiscardsScreenshot() = runTest {
        val store = newStore()
        store.installFreshToken("valid-token")
        val queue = FakeScreenshotQueue(QueuedScreenshot("id1", File("test.jpg"), "pkg"))
        val client = FakeScreenshotApiClient(ScreenshotUploadResult.OtherHttpError(400))
        val coordinator = ScreenshotUploadCoordinator(store, queue, client) { }

        coordinator.requestUpload()

        assertEquals(1, client.uploadCalls)
        assertEquals(1, queue.acknowledgeCalls)
        assertEquals("id1", queue.lastAcknowledgedId)
    }

    @Test
    fun otherHttpError404HaltsWithoutAcknowledging() = runTest {
        val store = newStore()
        store.installFreshToken("valid-token")
        val queue = FakeScreenshotQueue(QueuedScreenshot("id1", File("test.jpg"), "pkg"))
        val client = FakeScreenshotApiClient(ScreenshotUploadResult.OtherHttpError(404))
        val coordinator = ScreenshotUploadCoordinator(store, queue, client) { }

        coordinator.requestUpload()

        assertEquals(1, client.uploadCalls)
        assertEquals(0, queue.acknowledgeCalls)
    }

    @Test
    fun retryableErrorsHaltWithoutAcknowledging() = runTest {
        val errors = listOf(
            ScreenshotUploadResult.NetworkError(IOException("test")),
            ScreenshotUploadResult.Timeout,
            ScreenshotUploadResult.ServerError(500)
        )

        for (error in errors) {
            val store = newStore()
            store.installFreshToken("valid-token")
            val queue = FakeScreenshotQueue(QueuedScreenshot("id1", File("test.jpg"), "pkg"))
            val client = FakeScreenshotApiClient(error)
            val coordinator = ScreenshotUploadCoordinator(store, queue, client) { }

            coordinator.requestUpload()

            assertEquals(1, client.uploadCalls)
            assertEquals(0, queue.acknowledgeCalls)
        }
    }

    private suspend fun newStore(): NativeTokenStore {
        val store = NativeTokenStore(FakePreferencesDataStore(), FakeTokenCipher())
        return store
    }
}

private class FakeScreenshotQueue(
    private val screenshot: QueuedScreenshot?
) : ScreenshotQueue {
    var acknowledgeCalls = 0
    var lastAcknowledgedId: String? = null
    var enqueueCalls = 0

    override suspend fun enqueue(bitmap: android.graphics.Bitmap, packageName: String): EnqueueResult {
        enqueueCalls++
        return EnqueueResult.Enqueued
    }

    override suspend fun peekScreenshot(): QueuedScreenshot? {
        return if (acknowledgeCalls == 0) screenshot else null
    }

    override suspend fun acknowledge(screenshotId: String) {
        acknowledgeCalls++
        lastAcknowledgedId = screenshotId
    }
}

private class FakeScreenshotApiClient(
    private val result: ScreenshotUploadResult
) : ScreenshotApiClient {
    var uploadCalls = 0

    override suspend fun uploadScreenshot(
        token: String,
        screenshot: QueuedScreenshot
    ): ScreenshotUploadResult {
        uploadCalls++
        return result
    }
}

private class FakePreferencesDataStore : DataStore<Preferences> {
    private val state = MutableStateFlow<Preferences>(emptyPreferences())
    private val mutex = Mutex()

    override val data: Flow<Preferences> = state

    override suspend fun updateData(
        transform: suspend (t: Preferences) -> Preferences,
    ): Preferences = mutex.withLock {
        transform(state.value).also { state.value = it }
    }
}

private class FakeTokenCipher : TokenCipher {
    override fun encrypt(token: String): EncryptedToken = EncryptedToken(
        ciphertext = "encrypted:\$token",
        iv = "test-iv",
    )

    override fun decrypt(encodedCiphertext: String, encodedIv: String): String {
        require(encodedIv == "test-iv")
        return encodedCiphertext.removePrefix("encrypted:")
    }
}
