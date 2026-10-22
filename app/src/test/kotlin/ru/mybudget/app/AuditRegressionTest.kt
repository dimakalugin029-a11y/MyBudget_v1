package ru.mybudget.app

import org.junit.Assert.*
import org.junit.Test
import ru.mybudget.app.data.*
import java.time.*
import java.util.TimeZone

/** Regression checks for the application audit of 2026-10-21. */
class AuditRegressionTest {
    private fun inputs(goals: List<SavingsGoalEntity> = emptyList(),
        plans: List<MonthlyCategoryPlanEntity> = emptyList()) = ForecastHelper.Inputs(
        startBalance = 0.0, today = LocalDate.of(2026, 10, 21), horizon = ForecastHelper.Horizon.QUARTER,
        scenario = ForecastHelper.Scenario.OPTIMISTIC, incomeSources = emptyList(), obligations = emptyList(),
        monthlyPlans = plans, pastTransactions = emptyList(), goals = goals, goalBalances = emptyMap())

    @Test fun goalForecastMustNotExceedRemainingTarget() {
        val goal = SavingsGoalEntity(id = 1, name = "Цель", targetAmount = 1200.0, categoryId = 1, deadline = "2027-01-31")
        val actual = ForecastHelper.build(inputs(listOf(goal))).months.sumOf { it.goalsExpense }
        assertEquals("Remaining target is 1200, not a fresh target in every month", 1200.0, actual, 0.001)
    }

    @Test fun plannedGoalContributionMustNotBeCountedAgain() {
        val goal = SavingsGoalEntity(id = 1, name = "Цель", targetAmount = 1200.0, categoryId = 1, deadline = "2026-11-30")
        val plan = MonthlyCategoryPlanEntity(2026, 11, 1, 1, 1200.0)
        val actual = ForecastHelper.build(inputs(listOf(goal), listOf(plan))).months.first().totalExpense
        assertEquals("The same 1200 contribution already exists in the monthly plan", 1200.0, actual, 0.001)
    }

    @Test fun historicalBalanceMustExcludeSubsequentIncome() {
        fun ms(day: Int) = LocalDate.of(2026, 10, day).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val txs = listOf(TransactionEntity(categoryId = 1, amount = 100.0, type = "expense", description = "", date = ms(2)),
            TransactionEntity(categoryId = 1, amount = 500.0, type = "income", description = "", date = ms(5)))
        val actual = StatisticsChartHelper.buildBalanceSeries(txs, ms(1), ms(4) - 1,
            currentBalance = 1000.0, nowMs = ms(10)).endBalance
        assertEquals("500 rubles received after the selected period must be excluded", 500.0, actual, 0.001)
    }

    @Test fun firstDayMustNotBeUsedAsOpeningBalance() {
        val day = LocalDate.of(2026, 10, 1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val tx = TransactionEntity(categoryId = 1, amount = 100.0, type = "income", description = "", date = day + 1000)
        val actual = StatisticsChartHelper.buildBalanceSeries(listOf(tx), day, day + 86_399_999,
            currentBalance = 100.0, nowMs = day + 2000)
        assertEquals("Opening balance precedes the first day's income", 0.0, actual.startBalance, 0.001)
    }

    @Test fun lateNightFlowMustBelongToItsLocalDate() {
        val previousZone = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Europe/Moscow"))
            val date = LocalDate.of(2026, 10, 21)
            val start = date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            val tx = TransactionEntity(categoryId = 1, amount = 100.0, type = "income", description = "", date = start + 3_600_000)
            val point = StatisticsChartHelper.buildDailyFlowSeries(listOf(tx), start, start + 86_399_999).points.first { it.value != 0.0 }
            val displayed = Instant.ofEpochMilli(StatisticsChartHelper.dayKeyToMillis(point.dayKey)).atZone(ZoneId.systemDefault()).toLocalDate()
            assertEquals("01:00 Moscow must appear on October 21", date, displayed)
        } finally { TimeZone.setDefault(previousZone) }
    }

    @Test fun closedDisabledExpensePlanMustStayDisabledAfterFixation() {
        val category = BudgetCategory(id = 1, name = "Продукты", parentId = 0, plannedAmount = 1000.0)
        val disabled = MonthlyCategoryPlanEntity(2026, 9, 1, 1, 1000.0, isEnabled = false)
        val obligation = PlannedObligationEntity(budgetId = 1, name = "Платёж", amount = 500.0,
            periodType = "monthly", categoryId = 1, paychecksPerMonth = 1, dueMonth = 1)
        val actual = YearBudgetingHelper.expenseFixations(2026, 9, 1, listOf(category), listOf(disabled), listOf(obligation)).single()
        assertFalse("An explicit disabled plan must not be enabled by an obligation fallback", actual.isEnabled)
    }

    @Test fun validBonusScheduleRemainsCorrect() {
        val bonus = PlannedIncomeSourceEntity(id = 1, budgetId = 1, name = "Премия", amount = 12000.0,
            sourceType = "bonus", periodType = "yearly", dueMonth = 12)
        val actual = ForecastHelper.build(inputs().copy(incomeSources = listOf(bonus)))
        assertEquals(listOf(0.0, 12000.0, 0.0), actual.months.map { it.totalIncome })
    }

    @Test fun disabledForecastExpenseMustNotReturnFromHistoricalAverage() {
        val disabled = MonthlyCategoryPlanEntity(2026, 11, 1, 1, 0.0, isEnabled = false)
        val historic = TransactionEntity(categoryId = 1, amount = 3000.0, type = "expense", description = "",
            date = LocalDate.of(2026, 9, 15).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli())
        val actual = ForecastHelper.build(inputs(plans = listOf(disabled)).copy(pastTransactions = listOf(historic)))
        assertEquals("Explicitly disabled November plan must not reappear as average spending", 0.0, actual.months.first().totalExpense, 0.001)
    }

    @Test fun identicalNamesMustNotDuplicateFactInComparison() {
        val result = ForecastVsFactHelper.compareLines(listOf("Премия" to 1000.0, "Премия" to 500.0), mapOf("Премия" to 1500.0))
        assertEquals("Total received is 1500 even when two sources share a name", 1500.0, result.sumOf { it.factAmount }, 0.001)
    }

    @Test fun obligationWithExplicitTrackingDateIsShown() {
        val today = LocalDate.of(2026, 8, 31)
        val obligation = PlannedObligationEntity(budgetId = 1, name = "ОСАГО", amount = 12000.0,
            periodType = "yearly", categoryId = 5, paychecksPerMonth = 1, dueMonth = 9, dueDay = 15,
            attentionStartMonth = "2026-08")
        val entries = ru.mybudget.app.utilities.PaymentCalendarHelper.buildEntries(emptyList(), emptyList(), emptyList(),
            listOf(obligation), categoryNames = mapOf(5 to "Авто"), todayEpochDay = today.toEpochDay(), horizonDays = 30)
        assertTrue(entries.any { it.sourceRef.obligationId == obligation.id && it.amount == 12000.0 })
    }
}
