package ru.mybudget.app.setup

import android.content.Context
import ru.mybudget.app.BudgetApplication

object SetupChecklistPreferences {
    private const val KEY_DISMISSED = "setup_checklist_dismissed"

    fun isDismissed(context: Context): Boolean =
        prefs(context).getBoolean(KEY_DISMISSED, false)

    fun dismiss(context: Context) {
        prefs(context).edit().putBoolean(KEY_DISMISSED, true).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(BudgetApplication.PREFS_NAME, Context.MODE_PRIVATE)
}
