package app.remotex.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import app.remotex.net.normalizeRelayBaseUrl
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal interface TokenStore {
    fun load(): String
    fun save(token: String)
    fun clear()
}

internal fun relayScopeKey(relayUrl: String): String {
    val raw = relayUrl.trim().trimEnd('/')
    val normalizedBase = normalizeRelayBaseUrl(raw).getOrDefault(raw)
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(normalizedBase.toByteArray(Charsets.UTF_8))
    val hex = "0123456789abcdef"
    return buildString(24) {
        digest.take(12).forEach { byte ->
            val value = byte.toInt() and 0xff
            append(hex[value ushr 4])
            append(hex[value and 0x0f])
        }
    }
}

/** Stores the relay bearer token encrypted with a non-exportable app key. */
internal class SecureTokenStore(
    context: Context,
    private val scopeKey: String,
    private val preferencesName: String = "remotex.auth",
    private val keyAlias: String = "app.remotex.relay-token.$scopeKey",
) : TokenStore {
    private val preferences = context.applicationContext.getSharedPreferences(
        preferencesName,
        Context.MODE_PRIVATE,
    )

    @Synchronized
    override fun load(): String {
        val ciphertext = preferences.getString(ciphertextKey, null) ?: return ""
        val iv = preferences.getString(ivKey, null) ?: return ""
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateKey(),
                GCMParameterSpec(GCM_TAG_BITS, Base64.decode(iv, Base64.NO_WRAP)),
            )
            cipher.doFinal(Base64.decode(ciphertext, Base64.NO_WRAP)).toString(Charsets.UTF_8)
        } catch (_: Exception) {
            // Auto Backup can restore ciphertext without its device-bound key,
            // and device policy changes can invalidate a key. Fail signed out.
            clearInternal()
            ""
        }
    }

    @Synchronized
    override fun save(token: String) {
        val value = token.trim()
        if (value.isEmpty()) {
            clearInternal()
            return
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val ciphertext = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        preferences.edit()
            .putString(ivKey, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString(ciphertextKey, Base64.encodeToString(ciphertext, Base64.NO_WRAP))
            .commit()
    }

    @Synchronized
    override fun clear() {
        clearInternal()
    }

    private fun clearInternal() {
        preferences.edit().remove(ivKey).remove(ciphertextKey).commit()
        runCatching {
            val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
            if (keyStore.containsAlias(keyAlias)) keyStore.deleteEntry(keyAlias)
        }
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        (keyStore.getKey(keyAlias, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            KEYSTORE_PROVIDER,
        )
        generator.init(
            KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private val ivKey: String get() = "iv.$scopeKey"
    private val ciphertextKey: String get() = "ciphertext.$scopeKey"

    private companion object {
        const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
    }
}
