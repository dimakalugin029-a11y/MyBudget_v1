package ru.mybudget.app.setup

import android.content.Context
import ru.mybudget.app.security.WebDavSecrets

object WebDavBackupPreferences {
    private const val PREFS_NAME = "webdav_backup"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_BASE_URL = "base_url"
    private const val KEY_USERNAME = "username"
    private const val KEY_REMOTE_PATH = "remote_path"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
        if (!enabled) {
            WebDavSecrets.clear(context)
        }
    }

    fun baseUrl(context: Context): String = prefs(context).getString(KEY_BASE_URL, "").orEmpty().trim()

    fun setBaseUrl(context: Context, url: String) {
        prefs(context).edit().putString(KEY_BASE_URL, url.trim()).apply()
    }

    fun username(context: Context): String = prefs(context).getString(KEY_USERNAME, "").orEmpty().trim()

    fun setUsername(context: Context, username: String) {
        prefs(context).edit().putString(KEY_USERNAME, username.trim()).apply()
    }

    fun remotePath(context: Context): String {
        val raw = prefs(context).getString(KEY_REMOTE_PATH, "MyBudget").orEmpty().trim()
        return raw.trim('/').ifBlank { "MyBudget" }
    }

    fun setRemotePath(context: Context, path: String) {
        prefs(context).edit().putString(KEY_REMOTE_PATH, path.trim().trim('/')).apply()
    }

    fun canUpload(context: Context): Boolean {
        return isEnabled(context) &&
            baseUrl(context).isNotBlank() &&
            username(context).isNotBlank() &&
            WebDavSecrets.hasPassword(context)
    }
}
