package ru.mybudget.app.setup

import android.content.Context

object ParticipantPreferences {
    const val MAX_PARTICIPANTS = 8
    private const val PREFS = "participant_prefs"
    private const val KEY_NAMES = "names"

    fun getNames(context: Context): List<String> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_NAMES, "").orEmpty()
        return raw.split("\n", ",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(MAX_PARTICIPANTS)
    }

    fun setNames(context: Context, names: List<String>): Boolean {
        val cleaned = names.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (cleaned.size > MAX_PARTICIPANTS) return false
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_NAMES, cleaned.joinToString("\n"))
            .apply()
        return true
    }

    fun mergeNames(context: Context, imported: List<String>) {
        if (imported.isEmpty()) return
        val merged = (getNames(context) + imported.map { it.trim() }.filter { it.isNotEmpty() })
            .distinct()
            .take(MAX_PARTICIPANTS)
        setNames(context, merged)
    }
}
