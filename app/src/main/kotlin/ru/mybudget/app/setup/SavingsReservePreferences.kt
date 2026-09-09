package ru.mybudget.app.setup

import android.content.Context

object SavingsReservePreferences {
    private const val PREFS = "savings_reserve_prefs"
    private const val KEY_CATEGORY_ID = "reserve_category_id"
    private const val KEY_PERCENT = "reserve_percent"
    const val DEFAULT_PERCENT = 50
    val PERCENT_OPTIONS = listOf(5, 10, 15, 20, 25, 50, 75, 100)

    fun getReserveCategoryId(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_CATEGORY_ID, 0)

    fun setReserveCategoryId(context: Context, categoryId: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_CATEGORY_ID, categoryId)
            .apply()
    }

    fun getReservePercent(context: Context): Int {
        val stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_PERCENT, DEFAULT_PERCENT)
        return stored.takeIf { it in PERCENT_OPTIONS } ?: DEFAULT_PERCENT
    }

    fun setReservePercent(context: Context, percent: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_PERCENT, percent.coerceIn(1, 100))
            .apply()
    }
}
