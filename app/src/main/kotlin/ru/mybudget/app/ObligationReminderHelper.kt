package ru.mybudget.app

import ru.mybudget.app.data.PaymentReminder
import ru.mybudget.app.data.PlannedObligationEntity
import java.util.Calendar
import java.util.Date

object ObligationReminderHelper {
    fun suggestedDueDate(obligation: PlannedObligationEntity): Date {
        val today = Calendar.getInstance()
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 12)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        if (obligation.periodType == PlannedObligationHelper.PERIOD_YEARLY) {
            val month = obligation.dueMonth.coerceIn(1, 12)
            cal.set(Calendar.MONTH, month - 1)
            cal.set(Calendar.DAY_OF_MONTH, cal.getActualMaximum(Calendar.DAY_OF_MONTH))
            if (endOfDay(cal) < startOfDay(today)) {
                cal.add(Calendar.YEAR, 1)
                cal.set(Calendar.DAY_OF_MONTH, cal.getActualMaximum(Calendar.DAY_OF_MONTH))
            }
        } else {
            cal.set(Calendar.DAY_OF_MONTH, cal.getActualMaximum(Calendar.DAY_OF_MONTH))
            if (endOfDay(cal) <= startOfDay(today)) {
                cal.add(Calendar.MONTH, 1)
                cal.set(Calendar.DAY_OF_MONTH, cal.getActualMaximum(Calendar.DAY_OF_MONTH))
            }
        }
        return cal.time
    }

    fun buildReminder(obligation: PlannedObligationEntity, categoryName: String): PaymentReminder? {
        if (obligation.categoryId <= 0) return null
        val repeat = if (obligation.periodType == PlannedObligationHelper.PERIOD_YEARLY) {
            "once"
        } else {
            PlannedObligationHelper.PERIOD_MONTHLY
        }
        return PaymentReminder(
            title = obligation.name,
            amount = obligation.amount,
            categoryId = obligation.categoryId.toLong(),
            categoryName = categoryName,
            dueDate = suggestedDueDate(obligation),
            repeatType = repeat,
        )
    }

    private fun startOfDay(cal: Calendar): Long {
        return (cal.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    private fun endOfDay(cal: Calendar): Long {
        return (cal.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, 23)
            set(Calendar.MINUTE, 59)
            set(Calendar.SECOND, 59)
            set(Calendar.MILLISECOND, 999)
        }.timeInMillis
    }
}
