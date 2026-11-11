// Keeps the Claude API key on this phone only, encrypted with a key that never leaves Android's Keystore
package io.github.adenkushnir.datebook.pipeline
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
private const val KEYSTORE = "AndroidKeyStore"
private const val KEY_ALIAS = "datebook-claude-key"
private const val PREFS_NAME = "datebook-secrets"
private const val SAVED_KEY = "claude-key"
private const val SAVED_IV = "claude-key-iv"
private const val TRANSFORMATION = "AES/GCM/NoPadding"
private const val TAG_BITS = 128

// Saves, reads and deletes the Claude API key; only its encrypted form is ever written to storage
class ApiKeyStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // Encrypt the key and save it
    fun save(apiKey: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, keystoreKey())
        val encrypted = cipher.doFinal(apiKey.trim().toByteArray(Charsets.UTF_8))
        prefs.edit().putString(SAVED_KEY, encode(encrypted)).putString(SAVED_IV, encode(cipher.iv)).apply()
    }

    // Return the saved key, or null if none is saved or it can no longer be decrypted
    fun load(): String? {
        val encrypted = prefs.getString(SAVED_KEY, null)
        val iv = prefs.getString(SAVED_IV, null)

        // Nothing saved yet
        if (encrypted == null || iv == null) {
            return null
        }

        // Decrypt, treating a failure (for example after the Keystore was reset) as no key
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, keystoreKey(), GCMParameterSpec(TAG_BITS, decode(iv)))
            return String(cipher.doFinal(decode(encrypted)), Charsets.UTF_8)
        } catch (error: GeneralSecurityException) {  // Keystore key gone or saved data damaged
            return null
        }
    }

    // Delete the saved key
    fun clear() {
        prefs.edit().remove(SAVED_KEY).remove(SAVED_IV).apply()
    }

    // Return the Keystore key that encrypts the API key, creating it the first time
    private fun keystoreKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE)
        keyStore.load(null)
        val existing = keyStore.getKey(KEY_ALIAS, null) as SecretKey?

        // Reuse the key once it exists
        if (existing != null) {
            return existing
        }
        val purposes = KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        val spec = KeyGenParameterSpec.Builder(KEY_ALIAS, purposes)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .build()
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(spec)
        return generator.generateKey()
    }
}

// Show a saved key safely on screen, like sk-ant-api03-••••9f2a
fun maskKey(apiKey: String): String {
    // Very short keys are hidden completely
    if (apiKey.length < 20) {
        return "••••"
    }
    return apiKey.take(13) + "••••" + apiKey.takeLast(4)
}

// Turn bytes into text that can be saved in settings
private fun encode(bytes: ByteArray): String {
    return Base64.encodeToString(bytes, Base64.NO_WRAP)
}

// Turn saved text back into bytes
private fun decode(text: String): ByteArray {
    return Base64.decode(text, Base64.NO_WRAP)
}
