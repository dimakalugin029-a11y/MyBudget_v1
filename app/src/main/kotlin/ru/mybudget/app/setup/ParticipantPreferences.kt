package ru.mybudget.app.setup

import android.content.Context

object ParticipantPreferences {
    private const val PREFS = "participant_prefs"
    private const val KEY_NAMES = "names"

    fun getNames(context: Context): List<String> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_NAMES, "").orEmpty()
        return raw.split("\n", ",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
    }

    fun setNames(context: Context, names: List<String>) {
        val cleaned = names.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_NAMES, cleaned.joinToString("\n"))
            .apply()
    }
}
