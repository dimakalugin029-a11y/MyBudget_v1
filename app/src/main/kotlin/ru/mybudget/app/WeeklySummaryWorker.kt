package ru.mybudget.app

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import ru.mybudget.app.setup.WeeklySummaryPreferences

class WeeklySummaryWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        return try {
            if (!WeeklySummaryPreferences.isEnabled(applicationContext)) {
                return Result.success()
            }
            val digest = WeeklySummaryHelper.buildDigest(applicationContext) ?: return Result.success()
            WeeklySummaryNotifier.show(applicationContext, digest)
            Result.success()
        } catch (e: Exception) {
            e.printStackTrace()
            Result.retry()
        }
    }
}
