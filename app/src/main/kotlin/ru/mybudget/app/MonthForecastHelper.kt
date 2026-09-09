package ru.mybudget.app

import ru.mybudget.app.data.TransactionEntity
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

object MonthForecastHelper {
    data class MonthForecast(
        val projectedEndBalance: Double,
        val endDateLabel: String,
        val avgDailyNet: Double,
    )

    fun build(
        currentBalance: Double,
        monthTransactions: List<TransactionEntity>,
        today: LocalDate = LocalDate.now(),
    ): MonthForecast? {
        val daysElapsed = today.dayOfMonth.coerceAtLeast(1)
        val daysLeft = BudgetPlanHelper.daysLeftInMonth()
        if (daysLeft <= 0) return null

        val monthExpense = monthTransactions.filter { it.type == "expense" }.sumOf { it.amount }
        val monthIncome = monthTransactions.filter { it.type == "income" }.sumOf { it.amount }
        val avgDailyNet = MoneyFormat.roundMoney((monthIncome - monthExpense) / daysElapsed)
        val projectedEnd = MoneyFormat.roundMoney(currentBalance + avgDailyNet * daysLeft)

        val endDate = today.withDayOfMonth(today.lengthOfMonth())
        val endLabel = endDate.format(DateTimeFormatter.ofPattern("d.MM", Locale("ru")))

        return MonthForecast(
            projectedEndBalance = projectedEnd,
            endDateLabel = endLabel,
            avgDailyNet = avgDailyNet,
        )
    }
}
