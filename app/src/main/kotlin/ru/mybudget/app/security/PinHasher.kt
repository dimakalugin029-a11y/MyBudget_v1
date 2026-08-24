package ru.mybudget.app.security

import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

object PinHasher {
    private const val SALT_LENGTH_BYTES = 16
    private const val ITERATIONS = 120_000
    private const val KEY_LENGTH_BITS = 256

    private val algorithm: String by lazy {
        runCatching { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256") }
            .getOrNull()
            ?.let { "PBKDF2WithHmacSHA256" }
            ?: "PBKDF2WithHmacSHA1"
    }

    fun generateSalt(): ByteArray = ByteArray(SALT_LENGTH_BYTES).also {
        SecureRandom().nextBytes(it)
    }

    fun hashPin(pin: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, ITERATIONS, KEY_LENGTH_BITS)
        return SecretKeyFactory.getInstance(algorithm).generateSecret(spec).encoded
    }

    fun verifyPin(pin: String, salt: ByteArray, expectedHash: ByteArray): Boolean {
        return constantTimeEquals(hashPin(pin, salt), expectedHash)
    }

    fun verifyLegacyPin(pin: String, legacyHashCode: Int): Boolean = pin.hashCode() == legacyHashCode

    private fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var result = 0
        for (i in a.indices) {
            result = result or (a[i].toInt() xor b[i].toInt())
        }
        return result == 0
    }
}
