package ru.mybudget.app

import ru.mybudget.app.data.ObligationPaymentEntity
import ru.mybudget.app.data.PlannedObligationEntity
import ru.mybudget.app.utilities.PaymentCalendarHelper
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter

object ObligationPaymentHelper {
    data class PeriodKey(val obligationId: Int, val year: Int, val month: Int)

    private val isoFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    fun periodKey(obligationId: Int, dueDate: LocalDate): PeriodKey =
        PeriodKey(obligationId, dueDate.year, dueDate.monthValue)

    fun periodKey(entry: PaymentCalendarHelper.Entry): PeriodKey? {
        val obligationId = entry.sourceRef.obligationId ?: return null
        return periodKey(obligationId, LocalDate.ofEpochDay(entry.epochDay))
    }

    fun paidKeys(payments: List<ObligationPaymentEntity>): Set<PeriodKey> =
        payments.map { PeriodKey(it.obligationId, it.periodYear, it.periodMonth) }.toSet()

    fun isPaid(paid: Set<PeriodKey>, key: PeriodKey): Boolean = key in paid

    fun activePeriodDueDate(
        obligation: PlannedObligationEntity,
        today: LocalDate = LocalDate.now(),
    ): LocalDate? {
        if (obligation.periodType == PlannedObligationHelper.PERIOD_YEARLY) {
            val month = obligation.dueMonth.coerceIn(1, 12)
            if (today.monthValue != month) return null
            return PlannedObligationHelper.dueLocalDate(YearMonth.of(today.year, month), obligation.dueDay)
        }
        return PlannedObligationHelper.dueLocalDate(YearMonth.from(today), obligation.dueDay)
    }

    fun canPayNow(
        obligation: PlannedObligationEntity,
        paid: Set<PeriodKey>,
        today: LocalDate = LocalDate.now(),
    ): Boolean {
        if (obligation.categoryId <= 0 || !obligation.isActive) return false
        val dueDate = activePeriodDueDate(obligation, today) ?: return false
        return !isPaid(paid, periodKey(obligation.id, dueDate))
    }

    fun isPaidForActivePeriod(
        obligation: PlannedObligationEntity,
        paid: Set<PeriodKey>,
        today: LocalDate = LocalDate.now(),
    ): Boolean {
        val dueDate = activePeriodDueDate(obligation, today) ?: return false
        return isPaid(paid, periodKey(obligation.id, dueDate))
    }

    suspend fun payObligation(
        manager: BudgetManager,
        obligationId: Int,
        dueEpochDay: Long,
        amountOverride: Double? = null,
    ): Boolean {
        val obligation = manager.repository.getPlannedObligationById(obligationId) ?: return false
        if (obligation.categoryId <= 0) return false
        val dueDate = LocalDate.ofEpochDay(dueEpochDay)
        val key = periodKey(obligationId, dueDate)
        if (manager.repository.isObligationPeriodPaid(key.obligationId, key.year, key.month)) {
            return false
        }
        val amount = amountOverride ?: obligation.amount
        manager.recordTransaction(obligation.categoryId, amount, "expense", obligation.name)
        insertPaymentRecord(manager, key, amount)
        syncLinkedReminderAfterPay(manager, obligation, dueDate)
        syncLinkedRecurringAfterPay(manager, obligation, dueDate)
        return true
    }

    suspend fun markPeriodPaidFromDueDate(
        manager: BudgetManager,
        obligationId: Int?,
        dueDateIso: String,
        amount: Double,
    ) {
        if (obligationId == null || obligationId <= 0) return
        val dueDate = parseIsoDate(dueDateIso) ?: return
        val key = periodKey(obligationId, dueDate)
        if (manager.repository.isObligationPeriodPaid(key.obligationId, key.year, key.month)) return
        insertPaymentRecord(manager, key, amount)
    }

    private suspend fun insertPaymentRecord(
        manager: BudgetManager,
        key: PeriodKey,
        amount: Double,
    ) {
        manager.repository.insertObligationPayment(
            ObligationPaymentEntity(
                obligationId = key.obligationId,
                periodYear = key.year,
                periodMonth = key.month,
                amount = amount,
            ),
        )
    }

    private suspend fun syncLinkedReminderAfterPay(
        manager: BudgetManager,
        obligation: PlannedObligationEntity,
        dueDate: LocalDate,
    ) {
        val reminder = manager.repository.getReminderByObligationId(obligation.id) ?: return
        val reminderDate = parseIsoDate(reminder.dueDate) ?: return
        if (!samePeriod(reminderDate, dueDate)) return
        when (reminder.repeatType) {
            PlannedObligationHelper.PERIOD_MONTHLY -> {
                manager.repository.updateReminderDueDate(
                    reminder.id.toLong(),
                    reminderDate.plusMonths(1).format(isoFmt),
                )
            }
            else -> manager.repository.deleteReminder(reminder.id.toLong())
        }
    }

    private suspend fun syncLinkedRecurringAfterPay(
        manager: BudgetManager,
        obligation: PlannedObligationEntity,
        dueDate: LocalDate,
    ) {
        val recurring = manager.repository.getRecurringByObligationId(obligation.id) ?: return
        val recurringDate = parseIsoDate(recurring.nextDueDate) ?: return
        if (!samePeriod(recurringDate, dueDate)) return
        manager.repository.updateRecurringNextDate(
            recurring.id,
            recurringDate.plusMonths(1).format(isoFmt),
        )
    }

    private fun samePeriod(first: LocalDate, second: LocalDate): Boolean {
        return first.year == second.year && first.monthValue == second.monthValue
    }

    private fun parseIsoDate(value: String): LocalDate? =
        runCatching { LocalDate.parse(value, isoFmt) }.getOrNull()
}
