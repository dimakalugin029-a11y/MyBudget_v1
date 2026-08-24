package ru.mybudget.app.setup

import android.content.Context
import java.util.Calendar

object RolloverPreferences {
    private const val PREFS = "rollover_prefs"
    private const val KEY_LAST_ROLLOVER_YEAR = "last_rollover_year"
    private const val KEY_LAST_ROLLOVER_MONTH = "last_rollover_month"
    private const val KEY_PROMPT_DISMISSED_YEAR = "prompt_dismissed_year"
    private const val KEY_PROMPT_DISMISSED_MONTH = "prompt_dismissed_month"

    fun getLastRolloverYearMonth(context: Context): Pair<Int, Int>? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.contains(KEY_LAST_ROLLOVER_YEAR)) return null
        return prefs.getInt(KEY_LAST_ROLLOVER_YEAR, 0) to prefs.getInt(KEY_LAST_ROLLOVER_MONTH, 1)
    }

    fun markRolloverDone(context: Context) {
        val cal = Calendar.getInstance()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_LAST_ROLLOVER_YEAR, cal.get(Calendar.YEAR))
            .putInt(KEY_LAST_ROLLOVER_MONTH, cal.get(Calendar.MONTH) + 1)
            .apply()
    }

    fun shouldPromptRollover(context: Context): Boolean {
        val cal = Calendar.getInstance()
        val year = cal.get(Calendar.YEAR)
        val month = cal.get(Calendar.MONTH) + 1
        val last = getLastRolloverYearMonth(context)
        if (last != null && last.first == year && last.second == month) return false
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val dismissedYear = prefs.getInt(KEY_PROMPT_DISMISSED_YEAR, -1)
        val dismissedMonth = prefs.getInt(KEY_PROMPT_DISMISSED_MONTH, -1)
        return dismissedYear != year || dismissedMonth != month
    }

    fun dismissPromptForMonth(context: Context) {
        val cal = Calendar.getInstance()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_PROMPT_DISMISSED_YEAR, cal.get(Calendar.YEAR))
            .putInt(KEY_PROMPT_DISMISSED_MONTH, cal.get(Calendar.MONTH) + 1)
            .apply()
    }
}
