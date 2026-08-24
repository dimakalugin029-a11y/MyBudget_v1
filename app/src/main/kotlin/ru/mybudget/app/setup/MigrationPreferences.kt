package ru.mybudget.app.setup

import android.content.Context
import ru.mybudget.app.BudgetApplication

object MigrationPreferences {
    private const val KEY_UPGRADE_HINT_SHOWN = "upgrade_migration_hint_shown"

    fun isUpgradeHintShown(context: Context): Boolean =
        context.getSharedPreferences(BudgetApplication.PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_UPGRADE_HINT_SHOWN, false)

    fun markUpgradeHintShown(context: Context) {
        context.getSharedPreferences(BudgetApplication.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_UPGRADE_HINT_SHOWN, true)
            .apply()
    }
}
