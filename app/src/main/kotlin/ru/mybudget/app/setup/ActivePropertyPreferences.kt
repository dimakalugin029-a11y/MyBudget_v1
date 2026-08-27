package ru.mybudget.app.setup

import android.content.Context
import ru.mybudget.app.BudgetApplication

object ActivePropertyPreferences {
    const val DEFAULT_PROPERTY_ID = 1
    private const val KEY_ACTIVE_PROPERTY_ID = "active_utility_property_id"

    fun getActivePropertyId(context: Context): Int {
        return context.getSharedPreferences(BudgetApplication.PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_ACTIVE_PROPERTY_ID, DEFAULT_PROPERTY_ID)
    }

    fun setActivePropertyId(context: Context, propertyId: Int) {
        context.getSharedPreferences(BudgetApplication.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_ACTIVE_PROPERTY_ID, propertyId)
            .apply()
    }
}
