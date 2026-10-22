package ru.mybudget.app

import ru.mybudget.app.data.ObligationPaymentEntity
import ru.mybudget.app.data.PlannedObligationEntity
import ru.mybudget.app.utilities.PaymentCalendarHelper
import java.time.LocalDate
import java.time.YearMonth

object ObligationPaymentHelper {
    data class PeriodKey(val obligationId: Int, val year: Int, val month: Int)

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

    fun unpaidPeriodAmount(
        obligations: List<PlannedObligationEntity>,
        payments: List<ObligationPaymentEntity>,
        today: LocalDate = LocalDate.now(),
    ): Double {
        val paid = paidKeys(payments)
        var sum = 0.0
        for (obligation in obligations) {
            if (!obligation.isActive || obligation.categoryId <= 0) continue
            val dueDate = activePeriodDueDate(obligation, today) ?: continue
            if (!isPaid(paid, periodKey(obligation.id, dueDate))) {
                sum += obligation.amount
            }
        }
        return sum
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

    suspend fun payObligation(manager: BudgetManager, obligationId: Int, dueEpochDay: Long,
        amountOverride: Double? = null): Boolean {
        val result = PaymentExecution(manager.database).obligation(obligationId,
            LocalDate.ofEpochDay(dueEpochDay), true, amountOverride)
        manager.reloadCategoriesFromDatabase()
        if (result) manager.repository.getPlannedObligationById(obligationId)?.let {
            OverspendNotifier.checkAfterExpense(BudgetApplication.instance, it.categoryId)
        }
        return result
    }

    suspend fun completeObligation(manager: BudgetManager, obligationId: Int, dueEpochDay: Long): Boolean =
        PaymentExecution(manager.database).obligation(obligationId, LocalDate.ofEpochDay(dueEpochDay), false)
}
