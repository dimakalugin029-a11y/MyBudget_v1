package ru.mybudget.app.security

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.google.gson.annotations.SerializedName
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

object BackupCrypto {
    const val WRAPPER_VERSION = 1
    private const val GCM_TAG_LENGTH = 128
    private const val IV_LENGTH = 12
    private const val PBKDF2_ITERATIONS = 120_000

    private val gson = Gson()

    data class EncryptedBackupWrapper(
        @SerializedName("encrypted") val encrypted: Boolean = true,
        @SerializedName("wrapperVersion") val wrapperVersion: Int = WRAPPER_VERSION,
        @SerializedName("salt") val salt: String = "",
        @SerializedName("iv") val iv: String = "",
        @SerializedName("payload") val payload: String = "",
    )

    fun isEncryptedJson(content: String): Boolean {
        val trimmed = content.trim().removePrefix("\uFEFF")
        if (!trimmed.startsWith("{")) return false
        return try {
            val wrapper = gson.fromJson(trimmed, EncryptedBackupWrapper::class.java)
            wrapper?.encrypted == true && wrapper.payload.isNotBlank()
        } catch (_: JsonSyntaxException) {
            looksLikeEncryptedBackup(trimmed)
        }
    }

    /** Heuristic when wrapper JSON is truncated but still clearly an encrypted export. */
    fun looksLikeEncryptedBackup(content: String): Boolean {
        val trimmed = content.trim().removePrefix("\uFEFF")
        if (!trimmed.startsWith("{")) return false
        return trimmed.contains("\"encrypted\"") &&
            trimmed.contains("\"payload\"") &&
            Regex(""""encrypted"\s*:\s*true""").containsMatchIn(trimmed)
    }

    @OptIn(ExperimentalEncodingApi::class)
    fun encrypt(plainJson: String, password: String): String {
        val salt = PinHasher.generateSalt()
        val key = deriveKey(password, salt)
        val iv = ByteArray(IV_LENGTH).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_LENGTH, iv))
        val ciphertext = cipher.doFinal(plainJson.toByteArray(Charsets.UTF_8))
        val wrapper = EncryptedBackupWrapper(
            salt = Base64.encode(salt),
            iv = Base64.encode(iv),
            payload = Base64.encode(ciphertext),
        )
        return gson.toJson(wrapper)
    }

    @OptIn(ExperimentalEncodingApi::class)
    fun decrypt(encryptedContent: String, password: String): Result<String> {
        return try {
            val trimmed = encryptedContent.trim().removePrefix("\uFEFF")
            val wrapper = gson.fromJson(trimmed, EncryptedBackupWrapper::class.java)
                ?: return Result.failure(IllegalArgumentException("invalid wrapper"))
            if (!wrapper.encrypted || wrapper.payload.isBlank()) {
                return Result.failure(IllegalArgumentException("invalid wrapper"))
            }
            val salt = Base64.decode(wrapper.salt)
            val iv = Base64.decode(wrapper.iv)
            val ciphertext = Base64.decode(wrapper.payload)
            val key = deriveKey(password, salt)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_LENGTH, iv))
            val plain = cipher.doFinal(ciphertext).toString(Charsets.UTF_8)
            Result.success(plain)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun deriveKey(password: String, salt: ByteArray): ByteArray {
        val algorithm = runCatching { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256") }
            .getOrNull()
            ?.let { "PBKDF2WithHmacSHA256" }
            ?: "PBKDF2WithHmacSHA1"
        val spec = PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITERATIONS, 256)
        return SecretKeyFactory.getInstance(algorithm).generateSecret(spec).encoded
    }
}
