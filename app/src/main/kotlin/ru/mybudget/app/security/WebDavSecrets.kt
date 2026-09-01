package ru.mybudget.app.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object WebDavSecrets {
    private const val PREFS_NAME = "webdav_backup_secrets"
    private const val KEY_BLOB = "pwd_blob"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "mybudget_webdav_pwd"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_LENGTH = 128
    private const val IV_LENGTH = 12

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun hasPassword(context: Context): Boolean = !prefs(context).getString(KEY_BLOB, null).isNullOrBlank()

    fun savePassword(context: Context, password: String): Boolean {
        if (password.isBlank()) return false
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            val iv = cipher.iv
            val encrypted = cipher.doFinal(password.toByteArray(Charsets.UTF_8))
            val blob = ByteArray(iv.size + encrypted.size)
            System.arraycopy(iv, 0, blob, 0, iv.size)
            System.arraycopy(encrypted, 0, blob, iv.size, encrypted.size)
            prefs(context).edit().putString(KEY_BLOB, Base64.encodeToString(blob, Base64.NO_WRAP)).apply()
        }.isSuccess
    }

    fun getPassword(context: Context): String? {
        val encoded = prefs(context).getString(KEY_BLOB, null) ?: return null
        return runCatching {
            val blob = Base64.decode(encoded, Base64.NO_WRAP)
            require(blob.size > IV_LENGTH)
            val iv = blob.copyOfRange(0, IV_LENGTH)
            val encrypted = blob.copyOfRange(IV_LENGTH, blob.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_LENGTH, iv))
            cipher.doFinal(encrypted).toString(Charsets.UTF_8)
        }.getOrNull()
    }

    fun clear(context: Context) {
        prefs(context).edit().remove(KEY_BLOB).apply()
    }

    private fun getOrCreateKey(): SecretKey {
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
}
