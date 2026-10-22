package ru.mybudget.app

import ru.mybudget.app.data.MonthlyIncomePlanEntity
import ru.mybudget.app.data.PlannedIncomeSourceEntity
import ru.mybudget.app.data.TransactionEntity
import java.time.YearMonth

object IncomePlanAmounts {
    fun planned(source: PlannedIncomeSourceEntity, month: Int, override: MonthlyIncomePlanEntity? = null): Double {
        if (!source.isActive || override?.isEnabled == false) return 0.0
        return MoneyFormat.roundMoney((override?.amount ?: PlannedIncomeHelper.budgetMonthAmount(source, month)).coerceAtLeast(0.0))
    }

    data class Progress(val planned: Double, val received: Double) {
        val remaining: Double get() = MoneyFormat.roundMoney((planned - received).coerceAtLeast(0.0))
    }

    fun progress(source: PlannedIncomeSourceEntity, month: YearMonth, overrides: List<MonthlyIncomePlanEntity>,
        transactions: List<TransactionEntity>): Progress = Progress(
        planned(source, month.monthValue, overrides.firstOrNull { it.sourceId == source.id && it.year == month.year && it.month == month.monthValue }),
        MoneyFormat.roundMoney(transactions.filter { it.type == "income" && it.plannedIncomeSourceId == source.id &&
            it.plannedIncomeMonth == month.toString() }.sumOf { it.amount }),
    )
}
