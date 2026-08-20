package ru.mybudget.app.setup

import android.content.Context
import ru.mybudget.app.BudgetApplication

object ActiveBudgetPreferences {
    const val DEFAULT_BUDGET_ID = 1
    private const val KEY_ACTIVE_BUDGET_ID = "active_budget_id"

    fun getActiveBudgetId(context: Context): Int {
        return context.getSharedPreferences(BudgetApplication.PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_ACTIVE_BUDGET_ID, DEFAULT_BUDGET_ID)
    }

    fun setActiveBudgetId(context: Context, budgetId: Int) {
        context.getSharedPreferences(BudgetApplication.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_ACTIVE_BUDGET_ID, budgetId)
            .apply()
    }
}
