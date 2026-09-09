package ru.mybudget.app.setup

import android.content.Context
import ru.mybudget.app.BudgetApplication

object CompactUiPreferences {
    private const val KEY_ENABLED = "compact_ui_enabled"

    private fun prefs(context: Context) =
        context.getSharedPreferences(BudgetApplication.PREFS_NAME, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun shouldHideChecklist(context: Context): Boolean = isEnabled(context)

    fun shouldHideHints(context: Context): Boolean = isEnabled(context)
}
