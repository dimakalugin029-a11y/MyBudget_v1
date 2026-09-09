package ru.mybudget.app

import ru.mybudget.app.data.TransactionEntity
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

object MonthBudgetComparisonHelper {
    data class CategoryExpenseRow(
        val name: String,
        val amount: Double,
        val categoryIds: List<Int>,
    )

    data class MonthSnapshot(
        val year: Int,
        val month: Int,
        val label: String,
        val totals: StatisticsPeriodComparisonHelper.PeriodTotals,
        val topExpenses: List<CategoryExpenseRow>,
    )

    data class ComparisonResult(
        val monthA: MonthSnapshot,
        val monthB: MonthSnapshot,
        val incomeDelta: Double,
        val expenseDelta: Double,
        val saldoDelta: Double,
    )

    private val monthLabelFormat = SimpleDateFormat("LLLL yyyy", Locale("ru"))

    fun monthRangeMs(year: Int, month: Int): Pair<Long, Long> {
        val startCal = Calendar.getInstance().apply {
            set(Calendar.YEAR, year)
            set(Calendar.MONTH, month - 1)
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val startMs = startCal.timeInMillis
        startCal.add(Calendar.MONTH, 1)
        val endMs = startCal.timeInMillis - 1
        return startMs to endMs
    }

    fun monthLabel(year: Int, month: Int): String {
        val cal = Calendar.getInstance().apply {
            set(Calendar.YEAR, year)
            set(Calendar.MONTH, month - 1)
            set(Calendar.DAY_OF_MONTH, 1)
        }
        val raw = monthLabelFormat.format(cal.time)
        return raw.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale("ru")) else it.toString() }
    }

    fun buildSnapshot(
        year: Int,
        month: Int,
        transactions: List<TransactionEntity>,
        categories: List<BudgetCategory>,
        fromMs: Long,
        toMs: Long,
    ): MonthSnapshot {
        val inPeriod = transactions.filter { it.date in fromMs..toMs }
        val totals = StatisticsPeriodComparisonHelper.periodTotals(inPeriod)
        val topExpenses = topExpenseCategories(inPeriod, categories, limit = 5)
        return MonthSnapshot(
            year = year,
            month = month,
            label = monthLabel(year, month),
            totals = totals,
            topExpenses = topExpenses,
        )
    }

    fun compare(monthA: MonthSnapshot, monthB: MonthSnapshot): ComparisonResult {
        return ComparisonResult(
            monthA = monthA,
            monthB = monthB,
            incomeDelta = monthA.totals.income - monthB.totals.income,
            expenseDelta = monthA.totals.expense - monthB.totals.expense,
            saldoDelta = monthA.totals.saldo - monthB.totals.saldo,
        )
    }

    fun recentMonths(count: Int = 24, today: Calendar = Calendar.getInstance()): List<Pair<Int, Int>> {
        val result = mutableListOf<Pair<Int, Int>>()
        val cal = today.clone() as Calendar
        repeat(count) {
            result += cal.get(Calendar.YEAR) to (cal.get(Calendar.MONTH) + 1)
            cal.add(Calendar.MONTH, -1)
        }
        return result
    }

    private fun topExpenseCategories(
        transactions: List<TransactionEntity>,
        categories: List<BudgetCategory>,
        limit: Int,
    ): List<CategoryExpenseRow> {
        val grouped = linkedMapOf<Int, CategoryExpenseRow>()
        transactions
            .filter { it.type == "expense" && it.amount > 0.0 }
            .forEach { tx ->
                val category = categories.firstOrNull { it.id == tx.categoryId } ?: return@forEach
                val keyCategory = if (category.parentId != 0) {
                    categories.firstOrNull { it.id == category.parentId } ?: category
                } else {
                    category
                }
                val current = grouped[keyCategory.id]
                grouped[keyCategory.id] = CategoryExpenseRow(
                    name = keyCategory.name,
                    amount = (current?.amount ?: 0.0) + tx.amount,
                    categoryIds = ((current?.categoryIds ?: emptyList()) + category.id).distinct(),
                )
            }
        return grouped.values.sortedByDescending { it.amount }.take(limit)
    }
}
