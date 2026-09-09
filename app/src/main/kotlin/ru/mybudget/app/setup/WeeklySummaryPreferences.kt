package ru.mybudget.app.setup

import android.content.Context
import ru.mybudget.app.BudgetApplication

object WeeklySummaryPreferences {
    private const val KEY_ENABLED = "weekly_summary_enabled"

    private fun prefs(context: Context) =
        context.getSharedPreferences(BudgetApplication.PREFS_NAME, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }
}
