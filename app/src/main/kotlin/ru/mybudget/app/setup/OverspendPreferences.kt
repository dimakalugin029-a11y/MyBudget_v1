package ru.mybudget.app.setup

import android.content.Context
import ru.mybudget.app.BudgetApplication

object OverspendPreferences {
    private const val KEY_ENABLED = "overspend_notify_enabled"
    private const val KEY_THRESHOLD = "overspend_threshold_percent"

    private fun prefs(context: Context) =
        context.getSharedPreferences(BudgetApplication.PREFS_NAME, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun getThresholdPercent(context: Context): Int {
        return prefs(context).getInt(KEY_THRESHOLD, 100).coerceIn(50, 200)
    }

    fun setThresholdPercent(context: Context, percent: Int) {
        prefs(context).edit().putInt(KEY_THRESHOLD, percent.coerceIn(50, 200)).apply()
    }
}
