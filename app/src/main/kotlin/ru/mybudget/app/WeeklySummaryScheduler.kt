package ru.mybudget.app

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit

object WeeklySummaryScheduler {
    const val UNIQUE_WORK = "weekly-summary-digest"

    fun ensureScheduled(context: Context) {
        val initialDelayMs = initialDelayToNextMondayMorning()
        val workRequest = PeriodicWorkRequestBuilder<WeeklySummaryWorker>(7, TimeUnit.DAYS)
            .setInitialDelay(initialDelayMs, TimeUnit.MILLISECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            UNIQUE_WORK,
            ExistingPeriodicWorkPolicy.KEEP,
            workRequest,
        )
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK)
    }

    internal fun initialDelayToNextMondayMorning(): Long {
        val zone = ZoneId.systemDefault()
        val now = java.time.ZonedDateTime.now(zone)
        var targetDate = now.toLocalDate()
        while (targetDate.dayOfWeek != DayOfWeek.MONDAY) {
            targetDate = targetDate.plusDays(1)
        }
        var target = targetDate.atTime(LocalTime.of(9, 0)).atZone(zone)
        if (!target.isAfter(now)) {
            target = target.plusWeeks(1)
        }
        return Duration.between(now, target).toMillis().coerceAtLeast(0L)
    }
}
