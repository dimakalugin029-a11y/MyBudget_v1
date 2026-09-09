package ru.mybudget.app

import ru.mybudget.app.data.TransactionEntity
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

object YearOverviewHelper {
    data class MonthTotals(
        val month: Int,
        val label: String,
        val income: Double,
        val expense: Double,
        val saldo: Double,
    )

    data class YearOverview(
        val year: Int,
        val months: List<MonthTotals>,
        val totalIncome: Double,
        val totalExpense: Double,
        val totalSaldo: Double,
        val topExpenses: List<MonthBudgetComparisonHelper.CategoryExpenseRow>,
        val transactionCount: Int,
    )

    private val monthLabelFormat = SimpleDateFormat("LLL", Locale("ru"))

    fun build(
        year: Int,
        transactions: List<TransactionEntity>,
        categories: List<BudgetCategory>,
    ): YearOverview {
        val months = (1..12).map { month ->
            val (fromMs, toMs) = MonthBudgetComparisonHelper.monthRangeMs(year, month)
            val inPeriod = transactions.filter { it.date in fromMs..toMs }
            val totals = StatisticsPeriodComparisonHelper.periodTotals(inPeriod)
            MonthTotals(
                month = month,
                label = monthLabel(year, month),
                income = totals.income,
                expense = totals.expense,
                saldo = totals.saldo,
            )
        }
        val yearFrom = MonthBudgetComparisonHelper.monthRangeMs(year, 1).first
        val yearTo = MonthBudgetComparisonHelper.monthRangeMs(year, 12).second
        val yearTransactions = transactions.filter { it.date in yearFrom..yearTo }
        val totals = StatisticsPeriodComparisonHelper.periodTotals(yearTransactions)
        val topExpenses = MonthBudgetComparisonHelper.buildSnapshot(
            year = year,
            month = 1,
            transactions = yearTransactions,
            categories = categories,
            fromMs = yearFrom,
            toMs = yearTo,
        ).topExpenses
        return YearOverview(
            year = year,
            months = months,
            totalIncome = totals.income,
            totalExpense = totals.expense,
            totalSaldo = totals.saldo,
            topExpenses = topExpenses,
            transactionCount = yearTransactions.size,
        )
    }

    fun availableYears(transactions: List<TransactionEntity>, todayYear: Int = Calendar.getInstance().get(Calendar.YEAR)): List<Int> {
        val fromTx = transactions.map {
            Calendar.getInstance().apply { timeInMillis = it.date }.get(Calendar.YEAR)
        }
        val years = (fromTx + todayYear).distinct().sortedDescending()
        return if (years.isEmpty()) listOf(todayYear) else years
    }

    private fun monthLabel(year: Int, month: Int): String {
        val cal = Calendar.getInstance().apply {
            set(Calendar.YEAR, year)
            set(Calendar.MONTH, month - 1)
            set(Calendar.DAY_OF_MONTH, 1)
        }
        return monthLabelFormat.format(cal.time).replaceFirstChar {
            if (it.isLowerCase()) it.titlecase(Locale("ru")) else it.toString()
        }
    }
}
