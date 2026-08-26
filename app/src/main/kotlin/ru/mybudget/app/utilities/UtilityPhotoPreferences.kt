package ru.mybudget.app.utilities

import android.content.Context
import android.net.Uri

object UtilityPhotoPreferences {
    private const val PREFS_NAME = "utility_photos"
    private const val KEY_FOLDER_URI = "folder_uri"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun folderUri(context: Context): Uri? {
        val raw = prefs(context).getString(KEY_FOLDER_URI, null)?.trim().orEmpty()
        return raw.takeIf { it.isNotBlank() }?.let { Uri.parse(it) }
    }

    fun setFolderUri(context: Context, uri: Uri?) {
        prefs(context).edit().putString(KEY_FOLDER_URI, uri?.toString()).apply()
    }

    fun hasFolder(context: Context): Boolean = folderUri(context) != null
}
