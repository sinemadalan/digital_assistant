package com.example.accessibility_service

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val DATASTORE_NAME = "native_auth_token_store"
private val Context.nativeTokenDataStore: DataStore<Preferences> by preferencesDataStore(
    name = DATASTORE_NAME,
)
private val nativeTokenStoreMutex = Mutex()

class NativeTokenStore internal constructor(
    private val dataStore: DataStore<Preferences>,
    private val tokenCipher: TokenCipher,
) {
    constructor(context: Context) : this(
        dataStore = context.applicationContext.nativeTokenDataStore,
        tokenCipher = AndroidKeystoreTokenCipher(),
    )

    /**
     * Synchronizes an already-issued token without overriding a persisted revocation.
     *
     * The revocation check and write share the same process-wide mutex as [clearToken],
     * so a stale startup synchronization cannot race a 401 revocation back to valid.
     */
    suspend fun synchronizeExistingToken(token: String): Boolean {
        require(token.isNotBlank()) { "Authentication token must not be blank." }

        return nativeTokenStoreMutex.withLock {
            val preferences = dataStore.data.first()
            if (preferences[KEY_TOKEN_REVOKED] == true) {
                return@withLock false
            }

            val encryptedToken = withContext(Dispatchers.IO) { tokenCipher.encrypt(token) }
            dataStore.edit { preferences ->
                preferences[KEY_CIPHERTEXT] = encryptedToken.ciphertext
                preferences[KEY_IV] = encryptedToken.iv
                preferences[KEY_TOKEN_REVOKED] = false
            }
            true
        }
    }

    /** Installs a token returned by a successful, trusted enrollment. */
    internal suspend fun installFreshToken(token: String) {
        require(token.isNotBlank()) { "Authentication token must not be blank." }

        nativeTokenStoreMutex.withLock {
            val encryptedToken = withContext(Dispatchers.IO) { tokenCipher.encrypt(token) }
            dataStore.edit { preferences ->
                preferences[KEY_CIPHERTEXT] = encryptedToken.ciphertext
                preferences[KEY_IV] = encryptedToken.iv
                preferences[KEY_TOKEN_REVOKED] = false
            }
        }
    }

    suspend fun isReauthenticationRequired(): Boolean = nativeTokenStoreMutex.withLock {
        dataStore.data.first()[KEY_TOKEN_REVOKED] == true
    }

    suspend fun getToken(): String? = nativeTokenStoreMutex.withLock {
        val preferences = try {
            dataStore.data.first()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return@withLock null
        }

        if (preferences[KEY_TOKEN_REVOKED] != false) {
            return@withLock null
        }

        val encodedCiphertext = preferences[KEY_CIPHERTEXT] ?: return@withLock null
        val encodedIv = preferences[KEY_IV] ?: return@withLock null

        try {
            withContext(Dispatchers.IO) { tokenCipher.decrypt(encodedCiphertext, encodedIv) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    suspend fun clearToken() {
        nativeTokenStoreMutex.withLock {
            dataStore.edit { preferences ->
                preferences[KEY_TOKEN_REVOKED] = true
                preferences.remove(KEY_CIPHERTEXT)
                preferences.remove(KEY_IV)
            }
        }
    }

    suspend fun hasToken(): Boolean = getToken() != null

    private companion object {
        val KEY_CIPHERTEXT = stringPreferencesKey("ciphertext")
        val KEY_IV = stringPreferencesKey("iv")
        val KEY_TOKEN_REVOKED = booleanPreferencesKey("token_revoked")
    }
}

internal interface TokenCipher {
    fun encrypt(token: String): EncryptedToken
    fun decrypt(encodedCiphertext: String, encodedIv: String): String
}

internal data class EncryptedToken(
    val ciphertext: String,
    val iv: String,
)

private class AndroidKeystoreTokenCipher : TokenCipher {
    override fun encrypt(token: String): EncryptedToken {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
        val ciphertext = cipher.doFinal(token.toByteArray(StandardCharsets.UTF_8))
        return EncryptedToken(
            ciphertext = Base64.encodeToString(ciphertext, Base64.NO_WRAP),
            iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP),
        )
    }

    override fun decrypt(encodedCiphertext: String, encodedIv: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val iv = Base64.decode(encodedIv, Base64.NO_WRAP)
        cipher.init(Cipher.DECRYPT_MODE, getSecretKey(), GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
        val plaintext = cipher.doFinal(Base64.decode(encodedCiphertext, Base64.NO_WRAP))
        return String(plaintext, StandardCharsets.UTF_8)
    }

    private fun getOrCreateSecretKey(): SecretKey {
        val keyStore = loadKeyStore()
        val existingKey = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
        if (existingKey != null) {
            return existingKey
        }

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        keyGenerator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return keyGenerator.generateKey()
    }

    private fun getSecretKey(): SecretKey {
        return loadKeyStore().getKey(KEY_ALIAS, null) as? SecretKey
            ?: throw IllegalStateException("Native authentication key is unavailable.")
    }

    private fun loadKeyStore(): KeyStore {
        return KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
    }

    private companion object {
        const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        const val KEY_ALIAS = "digital_assistant_native_auth_token_aes"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_LENGTH_BITS = 128
    }
}
