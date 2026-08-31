package ru.mybudget.app

import ru.mybudget.app.data.PaymentReminder
import ru.mybudget.app.data.PaymentReminderEntity
import ru.mybudget.app.data.PlannedObligationEntity
import ru.mybudget.app.data.RecurringTransactionEntity
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
        return buildReminderEntity(obligation)?.toPaymentReminder(categoryName)
    }

    fun buildReminderEntity(obligation: PlannedObligationEntity): PaymentReminderEntity? {
        if (obligation.categoryId <= 0) return null
        val repeat = if (obligation.periodType == PlannedObligationHelper.PERIOD_YEARLY) {
            "once"
        } else {
            PlannedObligationHelper.PERIOD_MONTHLY
        }
        return PaymentReminderEntity(
            title = obligation.name,
            amount = obligation.amount,
            categoryId = obligation.categoryId,
            dueDate = isoFmt.format(suggestedDueDate(obligation)),
            repeatType = repeat,
            obligationId = obligation.id,
        )
    }

    fun buildRecurringEntity(obligation: PlannedObligationEntity): RecurringTransactionEntity? {
        if (obligation.categoryId <= 0) return null
        if (obligation.periodType != PlannedObligationHelper.PERIOD_MONTHLY) return null
        val nextDue = PlannedObligationHelper.nextDueDate(obligation)
        return RecurringTransactionEntity(
            categoryId = obligation.categoryId,
            amount = obligation.amount,
            type = "expense",
            description = obligation.name,
            repeatType = PlannedObligationHelper.PERIOD_MONTHLY,
            nextDueDate = isoFmt.format(Date.from(nextDue.atStartOfDay(ZoneId.systemDefault()).toInstant())),
            obligationId = obligation.id,
        )
    }
}
