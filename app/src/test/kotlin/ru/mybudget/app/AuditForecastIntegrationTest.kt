package ru.mybudget.app

import org.junit.Assert.*
import org.junit.Test
import ru.mybudget.app.data.*
import java.time.*

class AuditForecastIntegrationTest {
    private val today = LocalDate.of(2026, 10, 21)
    private fun inputs() = ForecastHelper.Inputs(1000.0, today, ForecastHelper.Horizon.QUARTER,
        ForecastHelper.Scenario.OPTIMISTIC,
        listOf(PlannedIncomeSourceEntity(id = 7, budgetId = 1, name = "Зарплата", amount = 1000.0, sourceType = "salary")),
        emptyList(), emptyList(), emptyList(), emptyList(), emptyMap(), includeCurrentMonth = true)

    @Test fun currentMonthSubtractsAlreadyReceivedIncomeAndHonorsNextMonthOverride() {
        val received = TransactionEntity(categoryId = 1, amount = 400.0, type = "income", description = "",
            date = today.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli())
        val result = ForecastHelper.build(inputs().copy(pastTransactions = listOf(received),
            monthlyIncomePlans = listOf(MonthlyIncomePlanEntity(2026, 11, 7, 1, 0.0, false))))
        assertEquals(4, result.months.size)
        assertTrue(result.months.first().isPartial)
        assertEquals(600.0, result.months.first().totalIncome, 0.001)
        assertEquals(0.0, result.months[1].totalIncome, 0.001)
    }

    @Test fun moneyAlreadyReservedForGoalIsNotShownAsAvailable() {
        val goal = SavingsGoalEntity(id = 1, name = "Резерв", targetAmount = 500.0, categoryId = 1)
        val result = ForecastHelper.build(inputs().copy(incomeSources = emptyList(), goals = listOf(goal), goalBalances = mapOf(1 to 500.0)))
        assertEquals(500.0, result.months.first().projectedEndBalance, 0.001)
    }

    @Test fun realObligationCannotBeHiddenBySmallerMonthlyLimit() {
        val obligation = PlannedObligationEntity(id = 9, budgetId = 1, name = "Страховка", amount = 12000.0,
            periodType = "yearly", categoryId = 1, paychecksPerMonth = 1, dueMonth = 11)
        val result = ForecastHelper.build(inputs().copy(obligations = listOf(obligation),
            monthlyPlans = listOf(MonthlyCategoryPlanEntity(2026, 11, 1, 1, 1000.0))))
        assertEquals(12000.0, result.months[1].totalExpense, 0.001)
    }

    @Test fun adjacentMonthsShareAnExclusiveBoundary() {
        val october = MonthBudgetComparisonHelper.monthRangeMs(2026, 10)
        val november = MonthBudgetComparisonHelper.monthRangeMs(2026, 11)
        assertEquals(october.second, november.first)
        val tx = TransactionEntity(categoryId = 1, amount = 100.0, type = "income", description = "", date = november.first)
        assertEquals(0.0, MonthBudgetComparisonHelper.buildSnapshot(2026, 10, listOf(tx), emptyList(), october.first, october.second).totals.income, 0.001)
        assertEquals(100.0, MonthBudgetComparisonHelper.buildSnapshot(2026, 11, listOf(tx), emptyList(), november.first, november.second).totals.income, 0.001)
    }
}
