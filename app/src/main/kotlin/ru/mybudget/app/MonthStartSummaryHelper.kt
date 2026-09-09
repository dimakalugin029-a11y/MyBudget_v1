package ru.mybudget.app

import android.content.Context
import ru.mybudget.app.data.MonthlyCategoryPlanEntity
import ru.mybudget.app.data.TransactionEntity

object MonthStartSummaryHelper {
    data class ExpenseLine(
        val name: String,
        val amount: Double,
        val planned: Double?,
    )

    data class Summary(
        val year: Int,
        val month: Int,
        val label: String,
        val income: Double,
        val expense: Double,
        val saldo: Double,
        val topExpenses: List<ExpenseLine>,
        val hasActivity: Boolean,
    )

    fun previousMonth(today: MonthlyPlanHelper.MonthKey = MonthlyPlanHelper.currentMonth()): MonthlyPlanHelper.MonthKey =
        MonthlyPlanHelper.shiftMonth(today.year, today.month, -1)

    fun build(
        transactions: List<TransactionEntity>,
        categories: List<BudgetCategory>,
        budgetId: Int,
        monthlyPlans: Map<Int, MonthlyCategoryPlanEntity>,
        year: Int,
        month: Int,
    ): Summary {
        val budgetCategoryIds = categories
            .filter { it.budgetId == budgetId && it.isActive }
            .map { it.id }
            .toSet()
        val filtered = transactions.filter { it.categoryId in budgetCategoryIds }
        val (fromMs, toMs) = MonthBudgetComparisonHelper.monthRangeMs(year, month)
        val snapshot = MonthBudgetComparisonHelper.buildSnapshot(
            year = year,
            month = month,
            transactions = filtered,
            categories = categories,
            fromMs = fromMs,
            toMs = toMs,
        )
        val topLines = snapshot.topExpenses.map { row ->
            ExpenseLine(
                name = row.name,
                amount = row.amount,
                planned = plannedForLeaves(row.categoryIds, categories, monthlyPlans).takeIf { it > 0.0 },
            )
        }
        val hasActivity = snapshot.totals.income > 0.01 || snapshot.totals.expense > 0.01
        return Summary(
            year = year,
            month = month,
            label = snapshot.label,
            income = snapshot.totals.income,
            expense = snapshot.totals.expense,
            saldo = snapshot.totals.saldo,
            topExpenses = topLines,
            hasActivity = hasActivity,
        )
    }

    fun formatBody(context: Context, summary: Summary): String {
        if (!summary.hasActivity) {
            return context.getString(R.string.month_start_summary_empty, summary.label)
        }
        return buildString {
            appendLine(
                context.getString(
                    R.string.month_start_summary_totals,
                    MoneyFormat.formatRub(summary.income),
                    MoneyFormat.formatRub(summary.expense),
                    MoneyFormat.formatRub(summary.saldo),
                ),
            )
            if (summary.topExpenses.isNotEmpty()) {
                appendLine()
                appendLine(context.getString(R.string.month_start_summary_top_title))
                summary.topExpenses.forEach { line ->
                    appendLine(formatExpenseLine(context, line))
                }
            }
        }.trimEnd()
    }

    private fun formatExpenseLine(context: Context, line: ExpenseLine): String {
        val amount = MoneyFormat.formatRub(line.amount)
        val planned = line.planned
        if (planned != null && planned > 0.0) {
            val overPct = kotlin.math.round(((line.amount / planned) - 1.0) * 100.0).toInt()
            return if (line.amount > planned + 0.01) {
                context.getString(
                    R.string.month_start_summary_expense_over,
                    line.name,
                    amount,
                    MoneyFormat.formatRub(planned),
                    overPct.coerceAtLeast(1),
                )
            } else {
                context.getString(
                    R.string.month_start_summary_expense_plan,
                    line.name,
                    amount,
                    MoneyFormat.formatRub(planned),
                )
            }
        }
        return context.getString(R.string.month_start_summary_expense_line, line.name, amount)
    }

    private fun plannedForLeaves(
        leafIds: List<Int>,
        categories: List<BudgetCategory>,
        monthlyPlans: Map<Int, MonthlyCategoryPlanEntity>,
    ): Double {
        return leafIds.sumOf { id ->
            val category = categories.firstOrNull { it.id == id } ?: return@sumOf 0.0
            MonthlyPlanHelper.effectivePlannedAmount(category, monthlyPlans[id])
        }
    }
}
