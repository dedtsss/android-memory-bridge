package net.brucegroup.memorybridge.data

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import net.brucegroup.memorybridge.domain.TextLimits
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class GatewaySettings(
    val gatewayUrl: String = "",
    val confirmBeforeSave: Boolean = true,
    val maxContextTokens: Int = 1200,
    val lastOperationId: String? = null,
    val lastOperationLabel: String? = null,
)

/** Stores non-secret preferences. The API token is deliberately kept out of this file. */
class GatewaySettingsRepository(context: Context) {
    private val prefs = context.getSharedPreferences("memory_bridge_settings", Context.MODE_PRIVATE)

    fun read(): GatewaySettings = GatewaySettings(
        gatewayUrl = prefs.getString(KEY_URL, "").orEmpty(),
        confirmBeforeSave = prefs.getBoolean(KEY_CONFIRM, true),
        maxContextTokens = TextLimits.clampContextTokens(prefs.getInt(KEY_MAX_TOKENS, 1200)),
        lastOperationId = prefs.getString(KEY_LAST_OPERATION, null),
        lastOperationLabel = prefs.getString(KEY_LAST_LABEL, null),
    )

    fun save(settings: GatewaySettings) {
        prefs.edit()
            .putString(KEY_URL, settings.gatewayUrl.trim())
            .putBoolean(KEY_CONFIRM, settings.confirmBeforeSave)
            .putInt(KEY_MAX_TOKENS, TextLimits.clampContextTokens(settings.maxContextTokens))
            .apply()
    }

    fun rememberUndo(operationId: String?, label: String?) {
        prefs.edit()
            .putString(KEY_LAST_OPERATION, operationId)
            .putString(KEY_LAST_LABEL, label)
            .apply()
    }

    fun clearUndo() {
        prefs.edit().remove(KEY_LAST_OPERATION).remove(KEY_LAST_LABEL).apply()
    }

    private companion object {
        const val KEY_URL = "gateway_url"
        const val KEY_CONFIRM = "confirm_before_save"
        const val KEY_MAX_TOKENS = "max_context_tokens"
        const val KEY_LAST_OPERATION = "last_operation_id"
        const val KEY_LAST_LABEL = "last_operation_label"
    }
}

/**
 * Encrypts the bearer token with a non-exportable AES key generated in Android Keystore.
 * Only ciphertext and its IV are persisted in private SharedPreferences.
 */
class SecureTokenStore(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("memory_bridge_secret", Context.MODE_PRIVATE)

    fun save(token: String) {
        if (token.isBlank()) {
            clear()
            return
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(token.toByteArray(Charsets.UTF_8))
        val packed = ByteBuffer.allocate(1 + cipher.iv.size + encrypted.size)
            .put(cipher.iv.size.toByte())
            .put(cipher.iv)
            .put(encrypted)
            .array()
        prefs.edit().putString(KEY_TOKEN, Base64.encodeToString(packed, Base64.NO_WRAP)).apply()
    }

    fun read(): String? {
        val encoded = prefs.getString(KEY_TOKEN, null) ?: return null
        return try {
            val packed = Base64.decode(encoded, Base64.NO_WRAP)
            val buffer = ByteBuffer.wrap(packed)
            val ivLength = buffer.get().toInt()
            require(ivLength in 12..32 && buffer.remaining() > ivLength)
            val iv = ByteArray(ivLength).also(buffer::get)
            val cipherText = ByteArray(buffer.remaining()).also(buffer::get)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(cipherText), Charsets.UTF_8)
        } catch (_: Exception) {
            // A key can be invalidated by the OS. Do not log secret material or ciphertext.
            clear()
            null
        }
    }

    fun hasToken(): Boolean = read() != null

    fun clear() {
        prefs.edit().remove(KEY_TOKEN).apply()
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
        if (existing != null) return existing.secretKey
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
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

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "memory_bridge_bearer_token_v1"
        const val KEY_TOKEN = "encrypted_token"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
