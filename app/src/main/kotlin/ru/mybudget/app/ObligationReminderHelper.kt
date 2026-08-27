package ru.mybudget.app

import ru.mybudget.app.data.PaymentReminder
import ru.mybudget.app.data.PlannedObligationEntity
import java.text.SimpleDateFormat
import java.time.ZoneId
import java.util.Date
import java.util.Locale

object ObligationReminderHelper {
    private val isoFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    fun suggestedDueDate(obligation: PlannedObligationEntity): Date {
        val localDate = PlannedObligationHelper.nextDueDate(obligation)
        return Date.from(localDate.atStartOfDay(ZoneId.systemDefault()).toInstant())
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
}
