package ru.mybudget.app

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object ReminderScheduler {
    const val UNIQUE_WORK = "daily-payment-reminders"

    fun ensureScheduled(context: Context) {
        val workRequest = PeriodicWorkRequestBuilder<RemindersWorker>(1, TimeUnit.DAYS).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            UNIQUE_WORK,
            ExistingPeriodicWorkPolicy.KEEP,
            workRequest,
        )
    }
}
