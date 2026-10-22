package ru.mybudget.app

import ru.mybudget.app.data.PlannedIncomeSourceEntity
import ru.mybudget.app.utilities.PaymentCalendarHelper
import java.time.LocalDate

object SalaryCycleHelper {
    data class SafeToSpendInfo(
        val dailyAmount: Double,
        val daysUntil: Int,
        val paydayDateLabel: String,
        val incomeSourceName: String,
        val committedUntilPayday: Double,
        val availableUntilPayday: Double,
        val isPaydayBased: Boolean,
    )

    fun compute(
        totalBalance: Double,
        incomeSources: List<PlannedIncomeSourceEntity>,
        paymentEntries: List<PaymentCalendarHelper.Entry>,
        today: LocalDate = LocalDate.now(),
        useCalendarIncome: Boolean = false,
    ): SafeToSpendInfo? {
        val todayEpoch = today.toEpochDay()
        val occurrences = if (useCalendarIncome) paymentEntries.filter { it.isIncome && it.sourceRef.incomeSourceId != null }
            .mapNotNull { entry ->
                incomeSources.firstOrNull { it.id == entry.sourceRef.incomeSourceId }?.let {
                    PlannedIncomeHelper.Occurrence(it, LocalDate.ofEpochDay(entry.epochDay), entry.epochDay)
                }
            }.sortedBy { it.epochDay }
        else PlannedIncomeHelper.occurrencesInHorizon(incomeSources, todayEpoch, horizonDays = 45)
        val next = occurrences
            .firstOrNull { it.epochDay >= todayEpoch }
            ?: return null

        val daysUntil = (next.epochDay - todayEpoch).toInt().coerceAtLeast(1)
        val committed = PaymentCalendarHelper.weekPaymentTotal(
            paymentEntries.filter { it.epochDay <= next.epochDay },
        )
        val available = MoneyFormat.roundMoney(totalBalance - committed)
        val daily = if (available <= 0.0) {
            0.0
        } else {
            MoneyFormat.roundMoney(available / daysUntil)
        }
        return SafeToSpendInfo(
            dailyAmount = daily,
            daysUntil = daysUntil,
            paydayDateLabel = PlannedIncomeHelper.formatOccurrenceDate(next.epochDay),
            incomeSourceName = next.source.name,
            committedUntilPayday = committed,
            availableUntilPayday = available,
            isPaydayBased = true,
        )
    }

    fun computeMonthFallback(totalBalance: Double): SafeToSpendInfo? {
        val daysLeft = BudgetPlanHelper.daysLeftInMonth()
        val daily = BudgetPlanHelper.safeToSpendDaily(totalBalance) ?: return null
        return SafeToSpendInfo(
            dailyAmount = daily,
            daysUntil = daysLeft,
            paydayDateLabel = "",
            incomeSourceName = "",
            committedUntilPayday = 0.0,
            availableUntilPayday = totalBalance,
            isPaydayBased = false,
        )
    }
}
