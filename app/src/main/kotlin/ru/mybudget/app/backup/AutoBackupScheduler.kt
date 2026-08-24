package ru.mybudget.app.backup

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import ru.mybudget.app.setup.AutoBackupPreferences
import java.util.concurrent.TimeUnit

object AutoBackupScheduler {
    private const val PERIODIC_WORK = "auto_backup_periodic"
    private const val ONE_SHOT_WORK = "auto_backup_once"

    fun ensureScheduled(context: Context) {
        if (AutoBackupPreferences.canRun(context)) {
            enqueuePeriodic(context, ExistingPeriodicWorkPolicy.KEEP)
        } else {
            cancel(context)
        }
    }

    fun applyFromSettings(context: Context, runImmediately: Boolean) {
        if (!AutoBackupPreferences.canRun(context)) {
            cancel(context)
            return
        }
        enqueuePeriodic(context, ExistingPeriodicWorkPolicy.UPDATE)
        if (runImmediately) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                ONE_SHOT_WORK,
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<AutoBackupWorker>().build(),
            )
        }
    }

    fun cancel(context: Context) {
        val wm = WorkManager.getInstance(context)
        wm.cancelUniqueWork(PERIODIC_WORK)
        wm.cancelUniqueWork(ONE_SHOT_WORK)
    }

    private fun enqueuePeriodic(context: Context, policy: ExistingPeriodicWorkPolicy) {
        val days = AutoBackupPreferences.intervalDays(context).toLong().coerceAtLeast(1L)
        val request = PeriodicWorkRequestBuilder<AutoBackupWorker>(days, TimeUnit.DAYS).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC_WORK, policy, request)
    }
}
