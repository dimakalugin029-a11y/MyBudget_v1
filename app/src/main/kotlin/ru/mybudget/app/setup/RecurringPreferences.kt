package ru.mybudget.app.setup

import android.content.Context
import ru.mybudget.app.BudgetApplication

object RecurringPreferences {
    private const val KEY_CONFIRM_BEFORE_APPLY = "recurring_confirm_before_apply"

    fun isConfirmBeforeApply(context: Context): Boolean {
        return context.getSharedPreferences(BudgetApplication.PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_CONFIRM_BEFORE_APPLY, true)
    }

    fun setConfirmBeforeApply(context: Context, enabled: Boolean) {
        context.getSharedPreferences(BudgetApplication.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_CONFIRM_BEFORE_APPLY, enabled)
            .apply()
    }
}
