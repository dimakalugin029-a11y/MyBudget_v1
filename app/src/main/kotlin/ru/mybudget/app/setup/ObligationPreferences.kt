package ru.mybudget.app.setup

import android.content.Context
import ru.mybudget.app.BudgetApplication

object ObligationPreferences {
    private const val KEY_PAYCHECKS_PER_MONTH = "obligation_paychecks_per_month"

    fun getPaychecksPerMonth(context: Context): Int {
        return context.getSharedPreferences(BudgetApplication.PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_PAYCHECKS_PER_MONTH, 2)
            .coerceIn(1, 4)
    }

    fun setPaychecksPerMonth(context: Context, count: Int) {
        context.getSharedPreferences(BudgetApplication.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_PAYCHECKS_PER_MONTH, count.coerceIn(1, 4))
            .apply()
    }
}
