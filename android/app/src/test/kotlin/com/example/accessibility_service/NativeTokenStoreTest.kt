package com.example.accessibility_service

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeTokenStoreTest {
    @Test
    fun freshTokenInstallMakesTokenUsableAndClearsReauthentication() = runTest {
        val store = newStore()

        store.installFreshToken("fresh-token")

        assertEquals("fresh-token", store.getToken())
        assertFalse(store.isReauthenticationRequired())
    }

    @Test
    fun revokeRemovesTokenAndRequiresReauthentication() = runTest {
        val store = newStore()
        store.installFreshToken("rejected-token")

        store.clearToken()

        assertNull(store.getToken())
        assertTrue(store.isReauthenticationRequired())
    }

    @Test
    fun reauthenticationStateSurvivesStoreReopen() = runTest {
        val dataStore = FakePreferencesDataStore()
        NativeTokenStore(dataStore, FakeTokenCipher()).clearToken()

        val reopenedStore = NativeTokenStore(dataStore, FakeTokenCipher())

        assertTrue(reopenedStore.isReauthenticationRequired())
        assertNull(reopenedStore.getToken())
    }

    @Test
    fun staleStartupSynchronizationCannotClearReauthentication() = runTest {
        val store = newStore()
        store.installFreshToken("old-token")
        store.clearToken()

        val accepted = store.synchronizeExistingToken("old-token")

        assertFalse(accepted)
        assertTrue(store.isReauthenticationRequired())
        assertNull(store.getToken())
    }

    @Test
    fun freshEnrollmentAfterReauthenticationIsExplicitlyAccepted() = runTest {
        val store = newStore()
        store.clearToken()

        store.installFreshToken("new-token")

        assertFalse(store.isReauthenticationRequired())
        assertEquals("new-token", store.getToken())
    }

    @Test
    fun repeatedRevokeIsIdempotent() = runTest {
        val store = newStore()

        store.clearToken()
        store.clearToken()

        assertTrue(store.isReauthenticationRequired())
        assertNull(store.getToken())
    }

    @Test
    fun revokeWinsAgainstConcurrentStaleSynchronization() = runTest {
        val store = newStore()
        store.installFreshToken("old-token")

        val synchronize = async { store.synchronizeExistingToken("old-token") }
        val revoke = async { store.clearToken() }
        synchronize.await()
        revoke.await()

        assertTrue(store.isReauthenticationRequired())
        assertNull(store.getToken())
    }


    @Test
    fun conditionalRevocationDoesNotClearFreshOrDifferentToken() = runTest {
        val store = newStore()
        store.installFreshToken("old")
        store.installFreshToken("fresh")
        assertFalse(store.clearTokenIfMatches("old"))
        assertEquals("fresh", store.getToken())
        assertFalse(store.isReauthenticationRequired())
        assertTrue(store.clearTokenIfMatches("fresh"))
        assertNull(store.getToken())
        assertTrue(store.isReauthenticationRequired())
        assertFalse(store.clearTokenIfMatches("fresh"))
    }

    @Test
    fun tokenAndReenrollmentFlowsObserveChangesAcrossStoreInstances() = runTest {
        val data = FakePreferencesDataStore()
        val first = NativeTokenStore(data, FakeTokenCipher())
        val second = NativeTokenStore(data, FakeTokenCipher())
        val events = kotlinx.coroutines.channels.Channel<String?>(kotlinx.coroutines.channels.Channel.UNLIMITED)
        val observer = backgroundScope.launch {
            second.tokenChanges.collect { events.send(it) }
        }
        assertNull(events.receive())
        first.installFreshToken("device-token")
        assertEquals("device-token", events.receive())
        assertTrue(first.clearTokenIfMatches("device-token"))
        assertNull(events.receive())
        assertTrue(second.reauthenticationChanges.first())
        first.installFreshToken("fresh-token")
        assertEquals("fresh-token", events.receive())
        assertFalse(second.reauthenticationChanges.first())
        observer.cancel()
    }

    private fun newStore(): NativeTokenStore = NativeTokenStore(
        FakePreferencesDataStore(),
        FakeTokenCipher(),
    )
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
        ciphertext = "encrypted:$token",
        iv = "test-iv",
    )

    override fun decrypt(encodedCiphertext: String, encodedIv: String): String {
        require(encodedIv == "test-iv")
        return encodedCiphertext.removePrefix("encrypted:")
    }
}
