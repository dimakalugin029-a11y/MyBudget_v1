package ru.mybudget.app.setup

import android.content.Context

object UtilityPaymentReminderPreferences {
    private const val PREFS_NAME = "utility_payment_reminder"
    private const val KEY_DAY_PREFIX = "payment_day_property_"
    private const val KEY_LEGACY_DAY = "payment_day_of_month"

    /** 1–31; 0 = последний день месяца (как у обязательных платежей). */
    const val DEFAULT_DAY = 10

    fun paymentDay(context: Context, propertyId: Int): Int {
        val prefs = prefs(context)
        val key = KEY_DAY_PREFIX + propertyId
        if (!prefs.contains(key) && propertyId == ActivePropertyPreferences.DEFAULT_PROPERTY_ID &&
            prefs.contains(KEY_LEGACY_DAY)
        ) {
            val legacy = prefs.getInt(KEY_LEGACY_DAY, DEFAULT_DAY)
            prefs.edit().putInt(key, legacy).apply()
        }
        val saved = prefs.getInt(key, DEFAULT_DAY)
        return if (saved == 0) 0 else saved.coerceIn(1, 31)
    }

    fun setPaymentDay(context: Context, propertyId: Int, day: Int) {
        val normalized = when {
            day <= 0 -> 0
            else -> day.coerceIn(1, 31)
        }
        prefs(context).edit()
            .putInt(KEY_DAY_PREFIX + propertyId, normalized)
            .apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
