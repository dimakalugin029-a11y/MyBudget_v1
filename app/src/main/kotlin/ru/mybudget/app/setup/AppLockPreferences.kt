package ru.mybudget.app.setup

import android.content.Context
import android.util.Base64
import ru.mybudget.app.BudgetApplication
import ru.mybudget.app.security.PinHasher

object AppLockPreferences {
    private const val KEY_ENABLED = "app_lock_enabled"
    private const val KEY_PIN_HASH_LEGACY = "app_lock_pin_hash"
    private const val KEY_PIN_SALT = "app_lock_pin_salt"
    private const val KEY_PIN_HASH = "app_lock_pin_hash_v2"
    private const val KEY_BIOMETRIC = "app_lock_biometric"
    private const val KEY_LAST_UNLOCK_MS = "app_lock_last_unlock_ms"
    const val LOCK_TIMEOUT_MS = 300_000L

    private fun prefs(context: Context) =
        context.getSharedPreferences(BudgetApplication.PREFS_NAME, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
        if (!enabled) clearPin(context)
    }

    fun isBiometricEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_BIOMETRIC, true)

    fun setBiometricEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_BIOMETRIC, enabled).apply()
    }

    fun hasPin(context: Context): Boolean {
        val p = prefs(context)
        return p.contains(KEY_PIN_HASH) || p.contains(KEY_PIN_HASH_LEGACY)
    }

    fun setPin(context: Context, pin: String) {
        val salt = PinHasher.generateSalt()
        val hash = PinHasher.hashPin(pin, salt)
        prefs(context).edit()
            .putString(KEY_PIN_SALT, encodeBytes(salt))
            .putString(KEY_PIN_HASH, encodeBytes(hash))
            .remove(KEY_PIN_HASH_LEGACY)
            .apply()
    }

    fun verifyPin(context: Context, pin: String): Boolean {
        val p = prefs(context)
        val saltStr = p.getString(KEY_PIN_SALT, null)
        val hashStr = p.getString(KEY_PIN_HASH, null)
        if (saltStr != null && hashStr != null) {
            return PinHasher.verifyPin(pin, decodeBytes(saltStr), decodeBytes(hashStr))
        }
        if (p.contains(KEY_PIN_HASH_LEGACY)) {
            val legacy = p.getInt(KEY_PIN_HASH_LEGACY, Int.MIN_VALUE)
            if (PinHasher.verifyLegacyPin(pin, legacy)) {
                setPin(context, pin)
                return true
            }
        }
        return false
    }

    fun markUnlocked(context: Context) {
        prefs(context).edit().putLong(KEY_LAST_UNLOCK_MS, System.currentTimeMillis()).apply()
    }

    fun shouldLock(context: Context, backgroundSinceMs: Long): Boolean {
        if (!isEnabled(context) || !hasPin(context)) return false
        val lastUnlock = prefs(context).getLong(KEY_LAST_UNLOCK_MS, 0L)
        if (lastUnlock == 0L) return true
        val backgroundElapsed = backgroundSinceMs <= 0L || System.currentTimeMillis() - backgroundSinceMs >= 500
        return backgroundElapsed && System.currentTimeMillis() - lastUnlock > LOCK_TIMEOUT_MS
    }

    private fun clearPin(context: Context) {
        prefs(context).edit()
            .remove(KEY_PIN_HASH_LEGACY)
            .remove(KEY_PIN_SALT)
            .remove(KEY_PIN_HASH)
            .apply()
    }

    private fun encodeBytes(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

    private fun decodeBytes(encoded: String): ByteArray = Base64.decode(encoded, Base64.NO_WRAP)
}
