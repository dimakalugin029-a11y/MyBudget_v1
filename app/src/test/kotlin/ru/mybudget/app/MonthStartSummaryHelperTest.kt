package ru.mybudget.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mybudget.app.data.MonthlyCategoryPlanEntity
import ru.mybudget.app.data.TransactionEntity

class MonthStartSummaryHelperTest {
    @Test
    fun previousMonth_shiftsFromCurrent() {
        val prev = MonthStartSummaryHelper.previousMonth(MonthlyPlanHelper.MonthKey(2026, 9))
        assertEquals(2026, prev.year)
        assertEquals(8, prev.month)
    }

    @Test
    fun build_aggregatesPreviousMonthForBudget() {
        val (fromMs, toMs) = MonthBudgetComparisonHelper.monthRangeMs(2026, 8)
        val categories = listOf(
            BudgetCategory(id = 1, name = "Быт", parentId = 0, budgetId = 1),
            BudgetCategory(id = 2, name = "Продукты", parentId = 1, budgetId = 1),
            BudgetCategory(id = 3, name = "Другое", parentId = 0, budgetId = 2),
        )
        val txs = listOf(
            TransactionEntity(categoryId = 2, amount = 5000.0, type = "expense", description = "", date = fromMs + 1),
            TransactionEntity(categoryId = 2, amount = 40_000.0, type = "income", description = "", date = fromMs + 2),
            TransactionEntity(categoryId = 3, amount = 999.0, type = "expense", description = "", date = fromMs + 3),
        )
        val plans = mapOf(
            2 to MonthlyCategoryPlanEntity(
                budgetId = 1,
                categoryId = 2,
                year = 2026,
                month = 8,
                plannedAmount = 4000.0,
                isEnabled = true,
            ),
        )

        val summary = MonthStartSummaryHelper.build(
            transactions = txs,
            categories = categories,
            budgetId = 1,
            monthlyPlans = plans,
            year = 2026,
            month = 8,
        )
        assertTrue(summary.hasActivity)
        assertEquals(40_000.0, summary.income, 0.01)
        assertEquals(5000.0, summary.expense, 0.01)
        assertEquals(1, summary.topExpenses.size)
        assertEquals("Быт", summary.topExpenses.first().name)
        assertEquals(4000.0, summary.topExpenses.first().planned ?: 0.0, 0.01)
    }

    @Test
    fun build_marksEmptyMonth() {
        val summary = MonthStartSummaryHelper.build(
            transactions = emptyList(),
            categories = listOf(BudgetCategory(id = 1, name = "Root", parentId = 0, budgetId = 1)),
            budgetId = 1,
            monthlyPlans = emptyMap(),
            year = 2026,
            month = 8,
        )

        assertFalse(summary.hasActivity)
    }
}
