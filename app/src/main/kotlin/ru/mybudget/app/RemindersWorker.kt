package ru.mybudget.app

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import ru.mybudget.app.data.BudgetDatabase
import ru.mybudget.app.data.BudgetRepository
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
            val delivered = applicationContext.getSharedPreferences("payment_notification_delivery", Context.MODE_PRIVATE)
            for (entity in dao.getOpenRemindersThrough(today)) {
                val due = runCatching { LocalDate.parse(entity.dueDate) }.getOrNull() ?: continue
                if (entity.obligationId != null) {
                    val obligation = dao.getPlannedObligationById(entity.obligationId)
                    if (obligation == null || !obligation.isActive) continue
                    if (dao.isObligationPeriodPaid(entity.obligationId, due.year, due.monthValue)) {
                        PaymentExecution(BudgetDatabase.getInstance(applicationContext)).reminder(entity.id, entity.dueDate, false)
                        continue
                    }
                    val linkedAuto = dao.getLinkedRecurring(entity.obligationId).any {
                        it.isActive && runCatching {
                            java.time.YearMonth.from(LocalDate.parse(it.nextDueDate)) == java.time.YearMonth.from(due)
                        }.getOrDefault(false)
                    }
                    if (linkedAuto) continue
                }
                val deliveryKey = "reminder_${entity.id}"
                val deliveryValue = "${entity.dueDate}:$today"
                if (delivered.getString(deliveryKey, null) == deliveryValue) continue
                val categoryName = repository.getCategoryName(entity.categoryId)
                notifier.showReminderNotification(entity.toPaymentReminder(categoryName))
                delivered.edit().putString(deliveryKey, deliveryValue).apply()
            }
            processRecurringTransactions(dao, repository, today)
            processInsuranceReminders()
            processMeterVerifications()
            MeterReadingReminderHelper.processReminder(applicationContext)
            Result.success()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            e.printStackTrace()
            Result.retry()
        }
    }

    private suspend fun processRecurringTransactions(
        dao: ru.mybudget.app.data.BudgetDao,
        repository: BudgetRepository,
        todayStr: String,
    ) {
        val due = dao.getRecurringDueBy(todayStr)
        val confirm = RecurringPreferences.isConfirmBeforeApply(applicationContext)
        for (recurring in due) {
            if (recurring.obligationId != null) {
                val obligation = dao.getPlannedObligationById(recurring.obligationId)
                if (obligation == null || !obligation.isActive) continue
                val date = runCatching { LocalDate.parse(recurring.nextDueDate) }.getOrNull() ?: continue
                if (dao.isObligationPeriodPaid(recurring.obligationId, date.year, date.monthValue)) {
                    PaymentExecution(BudgetDatabase.getInstance(applicationContext))
                        .recurring(recurring.id, recurring.nextDueDate)
                    continue
                }
            }
            if (confirm) {
                val categoryName = repository.getCategoryName(recurring.categoryId)
                RecurringNotification.showConfirmNotification(applicationContext, recurring, categoryName)
            } else {
                RecurringHelper.applyAndAdvance(recurring, applicationContext)
            }
        }
    }

    private suspend fun processInsuranceReminders() {
        val database = BudgetDatabase.getInstance(applicationContext)
        val autoDao = database.autoDao()
        val due = InsuranceReminderNotifier.filterDue(
            autoDao.getAllInsurances(),
            autoDao.getAllVehicles(),
            LocalDate.now().toEpochDay(),
        )
        InsuranceReminderNotifier.notifyDue(applicationContext, due)
    }

    private suspend fun processMeterVerifications() {
        val utilityDao = BudgetDatabase.getInstance(applicationContext).utilityDao()
        val allMeters = utilityDao.getAllProperties().flatMap { property ->
            utilityDao.getAllMeterInfo(property.id)
        }
        val due = MeterVerificationNotifier.filterDueWithinDays(allMeters, LocalDate.now().toEpochDay())
        MeterVerificationNotifier.notifyDueMeters(applicationContext, due)
    }
}
