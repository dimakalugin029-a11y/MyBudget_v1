package ru.mybudget.app

import org.junit.Assert.*
import org.junit.Test
import ru.mybudget.app.data.*
import java.time.YearMonth

class PlanOverviewHelperTest {
    private val month = YearMonth.of(2026, 10)
    private val date = MonthlyPlanHelper.monthRangeMs(2026, 10).first + 1000
    private fun source(id: Int = 1, budget: Int = 1) = PlannedIncomeSourceEntity(
        id = id, budgetId = budget, name = "Salary", amount = 1000.0, sourceType = "salary",
    )

    @Test fun allActualReceiptsAreVisibleButOnlyLinkedReceiptsReduceSpecificPlan() {
        val result = PlanOverviewHelper.summarize(1, month, listOf(BudgetCategory(1, "Income", 0)),
            listOf(source()), emptyList(), emptyList(), listOf(
                TransactionEntity(categoryId = 1, amount = 200.0, type = "income", description = "",
                    date = 0L, plannedIncomeSourceId = 1, plannedIncomeMonth = "2026-10"),
                TransactionEntity(categoryId = 1, amount = 500.0, type = "income", description = "", date = date),
            ))
        assertEquals(1000.0, result.incomePlanned, 0.001)
        assertEquals(700.0, result.incomeReceived, 0.001)
        assertEquals(500.0, result.incomeUnlinked, 0.001)
        assertEquals(800.0, result.incomeRemaining, 0.001)
    }

    @Test fun limitsDoNotCountParentTwiceAndRespectDisabledMonth() {
        val result = PlanOverviewHelper.summarize(1, month, listOf(
            BudgetCategory(1, "Parent", 0, plannedAmount = 900.0),
            BudgetCategory(2, "Enabled", 1, plannedAmount = 300.0),
            BudgetCategory(3, "Disabled", 1, plannedAmount = 400.0),
        ), emptyList(), emptyList(), listOf(MonthlyCategoryPlanEntity(2026, 10, 3, 1, 400.0, false)), emptyList())
        assertEquals(300.0, result.expensePlanned, 0.001)
    }

    @Test fun archiveFactsStayVisibleButOtherBudgetsAndNextMonthDoNotLeak() {
        val end = MonthlyPlanHelper.monthRangeMs(2026, 10).second
        val result = PlanOverviewHelper.summarize(1, month, listOf(
            BudgetCategory(1, "Archived", 0, isActive = false), BudgetCategory(2, "Other", 0, budgetId = 2),
        ), listOf(source(budget = 2)), emptyList(), emptyList(), listOf(
            TransactionEntity(categoryId = 1, amount = 40.0, type = "expense", description = "", date = date),
            TransactionEntity(categoryId = 1, amount = 80.0, type = "expense", description = "", date = end),
            TransactionEntity(categoryId = 2, amount = 100.0, type = "expense", description = "", date = date),
        ))
        assertEquals(40.0, result.expenseSpent, 0.001)
        assertFalse(result.hasIncomePlan)
    }

    @Test fun missingPlanAndExplicitZeroPlanHaveDifferentStates() {
        val categories = listOf(BudgetCategory(1, "Standalone", 0))
        val missing = PlanOverviewHelper.summarize(1, month, categories, emptyList(), emptyList(), emptyList(), emptyList())
        val disabled = PlanOverviewHelper.summarize(1, month, categories, emptyList(), emptyList(),
            listOf(MonthlyCategoryPlanEntity(2026, 10, 1, 1, 0.0, false)), emptyList())
        assertFalse(missing.hasExpensePlan)
        assertTrue(disabled.hasExpensePlan)
        assertEquals(0.0, disabled.expensePlanned, 0.001)
    }
}
