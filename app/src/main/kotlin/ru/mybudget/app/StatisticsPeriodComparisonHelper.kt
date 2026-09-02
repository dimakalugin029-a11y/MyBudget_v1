package ru.mybudget.app

import ru.mybudget.app.data.TransactionEntity

object StatisticsPeriodComparisonHelper {
    data class PeriodTotals(
        val income: Double,
        val expense: Double,
        val saldo: Double,
    )

    data class Comparison(
        val previousPeriodLabel: String,
        val current: PeriodTotals,
        val previous: PeriodTotals,
        val incomeDelta: Double,
        val expenseDelta: Double,
        val saldoDelta: Double,
    )

    fun previousRange(fromMs: Long, toMs: Long): Pair<Long, Long> {
        val duration = (toMs - fromMs).coerceAtLeast(0L)
        val prevTo = fromMs - 1
        val prevFrom = prevTo - duration
        return prevFrom to prevTo
    }

    fun periodTotals(transactions: List<TransactionEntity>): PeriodTotals {
        val income = transactions.filter { it.type == "income" }.sumOf { it.amount }
        val expense = transactions.filter { it.type == "expense" }.sumOf { it.amount }
        return PeriodTotals(income = income, expense = expense, saldo = income - expense)
    }

    fun buildComparison(
        currentTransactions: List<TransactionEntity>,
        previousTransactions: List<TransactionEntity>,
        previousPeriodLabel: String,
    ): Comparison {
        val current = periodTotals(currentTransactions)
        val previous = periodTotals(previousTransactions)
        return Comparison(
            previousPeriodLabel = previousPeriodLabel,
            current = current,
            previous = previous,
            incomeDelta = current.income - previous.income,
            expenseDelta = current.expense - previous.expense,
            saldoDelta = current.saldo - previous.saldo,
        )
    }

    fun formatDeltaAmount(delta: Double): String {
        val prefix = when {
            delta > 0.005 -> "+"
            delta < -0.005 -> "−"
            else -> ""
        }
        return prefix + MoneyFormat.formatRub(kotlin.math.abs(delta))
    }

    fun formatDeltaPercent(delta: Double, base: Double): String? {
        if (kotlin.math.abs(base) < 0.005) return null
        val pct = delta / base * 100.0
        val prefix = when {
            pct > 0.005 -> "+"
            pct < -0.005 -> "−"
            else -> ""
        }
        return prefix + String.format("%.0f%%", kotlin.math.abs(pct))
    }
}
