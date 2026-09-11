package lux.uno.anima.platform

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

actual fun createSecretStore(): SecretStore = AndroidSecretStore()

private const val KEYSTORE = "AndroidKeyStore"
private const val KEY_ALIAS = "anima-secrets"
private const val PREFERENCES = "anima-secrets"
private const val TRANSFORMATION = "AES/GCM/NoPadding"
private const val TAG_BITS = 128

/**
 * Encrypts with an AES key held in the Android Keystore and keeps only the ciphertext in
 * preferences. The key itself never enters the app's address space, so the token survives
 * someone reading the app's files off a rooted device.
 *
 * No user authentication is required to use the key. Requiring it would be stronger, but
 * sync runs in the background with the screen off, and a store that can only be read while
 * the user is looking at it is a store that cannot sync.
 */
private class AndroidSecretStore : SecretStore {

    override val protection = SecretProtection.OS_KEYSTORE

    private val preferences
        get() = AndroidPlatform.requireContext()
            .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    override suspend fun get(key: String): String? = withContext(Dispatchers.Default) {
        val stored = preferences.getString(key, null) ?: return@withContext null
        val parts = stored.split(':', limit = 2)
        if (parts.size != 2) return@withContext null
        runCatching {
            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            val ciphertext = Base64.decode(parts[1], Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, iv))
            cipher.doFinal(ciphertext).decodeToString()
        }.getOrElse {
            // Restoring a backup onto a new phone leaves the ciphertext without its key.
            // Drop it and report signed out rather than failing every launch from here on.
            preferences.edit().remove(key).apply()
            null
        }
    }

    override suspend fun put(key: String, value: String) = withContext(Dispatchers.Default) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val ciphertext = cipher.doFinal(value.encodeToByteArray())
        val encoded = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(ciphertext, Base64.NO_WRAP)
        preferences.edit().putString(key, encoded).apply()
        Unit
    }

    override suspend fun remove(key: String) = withContext(Dispatchers.Default) {
        preferences.edit().remove(key).apply()
        Unit
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }
}
