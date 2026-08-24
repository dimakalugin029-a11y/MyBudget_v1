package ru.mybudget.app

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import ru.mybudget.app.data.BudgetDatabase
import ru.mybudget.app.data.BudgetRepository
import ru.mybudget.app.data.PaymentReminderEntity
import ru.mybudget.app.setup.RecurringPreferences
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.util.Calendar
import java.util.Locale

class RemindersWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    override suspend fun doWork(): Result {
        return try {
            val dao = BudgetDatabase.getInstance(applicationContext).budgetDao()
            val repository = BudgetRepository(dao)
            val notifier = ReminderNotification(applicationContext)
            val today = dateFormat.format(Calendar.getInstance().time)
            for (entity in dao.getRemindersDueToday(today)) {
                val categoryName = repository.getCategoryName(entity.categoryId)
                notifier.showReminderNotification(entity.toPaymentReminder(categoryName))
                val next = computeNextDueDate(entity)
                if (next != null) {
                    repository.updateReminderDueDate(entity.id.toLong(), next)
                } else {
                    repository.deleteReminder(entity.id.toLong())
                }
            }
            processRecurringTransactions(dao, repository, today)
            processMeterVerifications()
            Result.success()
        } catch (e: Exception) {
            e.printStackTrace()
            Result.retry()
        }
    }

    private fun computeNextDueDate(entity: PaymentReminderEntity): String? {
        val current = runCatching { dateFormat.parse(entity.dueDate) }.getOrNull() ?: return null
        val calendar = Calendar.getInstance().apply { time = current }
        when (entity.repeatType) {
            "daily" -> calendar.add(Calendar.DAY_OF_YEAR, 1)
            "weekly" -> calendar.add(Calendar.WEEK_OF_YEAR, 1)
            PlannedObligationHelper.PERIOD_MONTHLY -> calendar.add(Calendar.MONTH, 1)
            else -> return null
        }
        return dateFormat.format(calendar.time)
    }

    private suspend fun processRecurringTransactions(
        dao: ru.mybudget.app.data.BudgetDao,
        repository: BudgetRepository,
        todayStr: String,
    ) {
        val due = dao.getRecurringDueBy(todayStr)
        val confirm = RecurringPreferences.isConfirmBeforeApply(applicationContext)
        for (recurring in due) {
            if (confirm) {
                val categoryName = repository.getCategoryName(recurring.categoryId)
                RecurringNotification.showConfirmNotification(applicationContext, recurring, categoryName)
            } else {
                RecurringHelper.applyAndAdvance(repository, dao, recurring, applicationContext)
            }
        }
    }

    private suspend fun processMeterVerifications() {
        val meters = BudgetDatabase.getInstance(applicationContext).utilityDao().getAllMeterInfo()
        val due = MeterVerificationNotifier.filterDueWithinDays(meters, LocalDate.now().toEpochDay())
        MeterVerificationNotifier.notifyDueMeters(applicationContext, due)
    }
}
