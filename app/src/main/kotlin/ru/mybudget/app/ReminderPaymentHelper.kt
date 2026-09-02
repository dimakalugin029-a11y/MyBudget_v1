package ru.mybudget.app

import ru.mybudget.app.data.PaymentReminderEntity
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

object ReminderPaymentHelper {
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    suspend fun payReminder(manager: BudgetManager, reminderId: Int): Boolean {
        val entity = manager.repository.getReminderById(reminderId) ?: return false
        manager.recordTransaction(entity.categoryId, entity.amount, "expense", entity.title)
        ObligationPaymentHelper.markPeriodPaidFromDueDate(
            manager,
            entity.obligationId,
            entity.dueDate,
            entity.amount,
        )
        advanceOrClose(manager, entity)
        return true
    }

    private suspend fun advanceOrClose(manager: BudgetManager, entity: PaymentReminderEntity) {
        val next = nextDueDate(entity)
        if (next != null) {
            manager.repository.updateReminderDueDate(entity.id.toLong(), next)
        } else {
            manager.repository.deleteReminder(entity.id.toLong())
        }
    }

    private fun nextDueDate(entity: PaymentReminderEntity): String? {
        val current = runCatching { dateFormat.parse(entity.dueDate) }.getOrNull() ?: return null
        val calendar = Calendar.getInstance().apply { time = current }
        when (entity.repeatType) {
            "daily" -> calendar.add(Calendar.DAY_OF_YEAR, 1)
            "weekly" -> calendar.add(Calendar.WEEK_OF_YEAR, 1)
            "monthly" -> calendar.add(Calendar.MONTH, 1)
            else -> return null
        }
        return dateFormat.format(calendar.time)
    }
}
