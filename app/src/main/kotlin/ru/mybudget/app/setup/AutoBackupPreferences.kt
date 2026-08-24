package ru.mybudget.app.setup

import android.content.Context
import android.net.Uri
import ru.mybudget.app.security.AutoBackupSecrets

object AutoBackupPreferences {
    val INTERVAL_DAYS = intArrayOf(7, 14, 30, 60)

    private const val PREFS_NAME = "auto_backup"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_INTERVAL_DAYS = "interval_days"
    private const val KEY_ENCRYPT = "encrypt"
    private const val KEY_FOLDER_URI = "folder_uri"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
        if (!enabled) {
            setEncrypt(context, false)
        }
    }

    fun intervalDays(context: Context): Int {
        val stored = prefs(context).getInt(KEY_INTERVAL_DAYS, 7)
        return if (stored in INTERVAL_DAYS) stored else 7
    }

    fun intervalIndex(context: Context): Int {
        val days = intervalDays(context)
        return INTERVAL_DAYS.indexOf(days).coerceAtLeast(0)
    }

    fun setIntervalDays(context: Context, days: Int) {
        val value = if (days in INTERVAL_DAYS) days else 7
        prefs(context).edit().putInt(KEY_INTERVAL_DAYS, value).apply()
    }

    fun isEncryptEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENCRYPT, false)

    fun setEncrypt(context: Context, encrypt: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENCRYPT, encrypt).apply()
        if (!encrypt) AutoBackupSecrets.clear(context)
    }

    fun folderUri(context: Context): Uri? {
        val raw = prefs(context).getString(KEY_FOLDER_URI, null)?.trim().orEmpty()
        return raw.takeIf { it.isNotBlank() }?.let { Uri.parse(it) }
    }

    fun setFolderUri(context: Context, uri: Uri?) {
        prefs(context).edit().putString(KEY_FOLDER_URI, uri?.toString()).apply()
    }

    fun canRun(context: Context): Boolean =
        isEnabled(context) && folderUri(context) != null &&
            (!isEncryptEnabled(context) || AutoBackupSecrets.hasPassword(context))
}
