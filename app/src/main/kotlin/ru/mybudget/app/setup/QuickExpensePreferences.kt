package ru.mybudget.app.setup

import android.content.Context

object QuickExpensePreferences {
    private const val PREFS_NAME = "quick_expense"
    private const val KEY_LAST_CATEGORY_ID = "last_category_id"
    private const val KEY_LAST_AMOUNT = "last_amount"
    private const val KEY_LAST_DESCRIPTION = "last_description"

    fun getLastCategoryId(context: Context): Int {
        return prefs(context).getInt(KEY_LAST_CATEGORY_ID, -1)
    }

    fun getLastAmount(context: Context): Double? {
        val raw = prefs(context).getString(KEY_LAST_AMOUNT, null) ?: return null
        return raw.toDoubleOrNull()?.takeIf { it > 0.0 }
    }

    fun getLastDescription(context: Context): String? {
        return prefs(context).getString(KEY_LAST_DESCRIPTION, null)?.takeIf { it.isNotBlank() }
    }

    fun saveLastExpense(context: Context, categoryId: Int, amount: Double, description: String) {
        prefs(context).edit()
            .putInt(KEY_LAST_CATEGORY_ID, categoryId)
            .putString(KEY_LAST_AMOUNT, amount.toString())
            .putString(KEY_LAST_DESCRIPTION, description)
            .apply()
    }

    fun hasRepeatableExpense(context: Context): Boolean {
        return getLastCategoryId(context) > 0 && getLastAmount(context) != null
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
