package ru.mybudget.app.setup

import android.content.Context

object MeterReadingReminderPreferences {
    private const val PREFS_NAME = "meter_reading_reminder"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_DAY = "day_of_month"
    private const val KEY_LAST_NOTIFIED = "last_notified_month"

    const val DEFAULT_DAY = 15
    val DAY_OPTIONS: List<Int> = (1..31).toList()

    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun reminderDay(context: Context): Int {
        val saved = prefs(context).getInt(KEY_DAY, DEFAULT_DAY)
        return saved.coerceIn(DAY_OPTIONS.first(), DAY_OPTIONS.last())
    }

    fun setReminderDay(context: Context, day: Int) {
        prefs(context).edit()
            .putInt(KEY_DAY, day.coerceIn(DAY_OPTIONS.first(), DAY_OPTIONS.last()))
            .apply()
    }

    fun dayIndex(context: Context): Int {
        val day = reminderDay(context)
        return DAY_OPTIONS.indexOf(day).takeIf { it >= 0 } ?: DAY_OPTIONS.indexOf(DEFAULT_DAY)
    }

    fun getLastNotifiedMonth(context: Context): String? {
        return prefs(context).getString(KEY_LAST_NOTIFIED, null)?.takeIf { it.isNotBlank() }
    }

    fun setLastNotifiedMonth(context: Context, monthKey: String) {
        prefs(context).edit().putString(KEY_LAST_NOTIFIED, monthKey).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
