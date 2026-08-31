package ru.mybudget.app

import android.content.Context
import ru.mybudget.app.data.BudgetRepository

object ObligationUpgradeHelper {
    private const val PREFS_NAME = "obligation_upgrade"
    private const val KEY_LINKED_SYNC_V31 = "linked_sync_v31"

    suspend fun runLinkedSyncOnceIfNeeded(context: Context, repository: BudgetRepository) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_LINKED_SYNC_V31, false)) return
        repository.getAllPlannedObligationsForExport()
            .filter { it.isActive && (it.remindEnabled || it.autoPostEnabled) }
            .forEach { ObligationLinkedSync.sync(repository, it) }
        prefs.edit().putBoolean(KEY_LINKED_SYNC_V31, true).apply()
    }
}
