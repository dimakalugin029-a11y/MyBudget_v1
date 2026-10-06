package ru.mybudget.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mybudget.app.data.MonthlyCategoryPlanEntity
import ru.mybudget.app.data.PlannedIncomeSourceEntity
import ru.mybudget.app.data.PlannedObligationEntity
import ru.mybudget.app.data.SavingsGoalEntity
import ru.mybudget.app.data.TransactionEntity
import java.time.LocalDate
import java.time.YearMonth

class ForecastHelperTest {
    private val today: LocalDate = LocalDate.of(2026, 10, 15)

    private fun income(
        id: Int = 1,
        name: String = "Зарплата",
        amount: Double = 50000.0,
        periodType: String = "monthly",
        dueMonth: Int = 1,
    ) = PlannedIncomeSourceEntity(
        id = id,
        budgetId = 1,
        name = name,
        amount = amount,
        sourceType = "salary",
        periodType = periodType,
        dueMonth = dueMonth,
    )

    private fun obligation(
        id: Int = 1,
        name: String = "Кредит",
        amount: Double = 10000.0,
        periodType: String = "monthly",
        categoryId: Int = 10,
        dueMonth: Int = 1,
    ) = PlannedObligationEntity(
        id = id,
        budgetId = 1,
        name = name,
        amount = amount,
        periodType = periodType,
        categoryId = categoryId,
        paychecksPerMonth = 1,
        dueMonth = dueMonth,
    )

    private fun plan(
        year: Int,
        month: Int,
        categoryId: Int,
        amount: Double,
    ) = MonthlyCategoryPlanEntity(
        year = year,
        month = month,
        categoryId = categoryId,
        budgetId = 1,
        plannedAmount = amount,
    )

    private fun tx(
        categoryId: Int,
        amount: Double,
        type: String,
        date: LocalDate,
    ) = TransactionEntity(
        categoryId = categoryId,
        amount = amount,
        type = type,
        description = "",
        date = date.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli(),
    )

    private fun goal(
        id: Int = 1,
        name: String = "Цель",
        target: Double = 60000.0,
        categoryId: Int = 30,
        deadline: String = "2026-12-31",
        isActive: Boolean = true,
    ) = SavingsGoalEntity(
        id = id,
        name = name,
        targetAmount = target,
        categoryId = categoryId,
        deadline = deadline,
        isActive = isActive,
    )

    private fun inputs(
        horizon: ForecastHelper.Horizon = ForecastHelper.Horizon.QUARTER,
        scenario: ForecastHelper.Scenario = ForecastHelper.Scenario.OPTIMISTIC,
        incomeSources: List<PlannedIncomeSourceEntity> = listOf(income()),
        obligations: List<PlannedObligationEntity> = listOf(obligation()),
        monthlyPlans: List<MonthlyCategoryPlanEntity> = emptyList(),
        pastTransactions: List<TransactionEntity> = emptyList(),
        goals: List<SavingsGoalEntity> = emptyList(),
        goalBalances: Map<Int, Double> = emptyMap(),
    ) = ForecastHelper.Inputs(
        startBalance = 10000.0,
        today = today,
        horizon = horizon,
        scenario = scenario,
        incomeSources = incomeSources,
        obligations = obligations,
        monthlyPlans = monthlyPlans,
        pastTransactions = pastTransactions,
        goals = goals,
        goalBalances = goalBalances,
    )

    @Test
    fun `quarter produces three months starting next month`() {
        val result = ForecastHelper.build(inputs())
        assertEquals(3, result.months.size)
        assertEquals(2026, result.months[0].year)
        assertEquals(11, result.months[0].month)
        assertEquals(2027, result.months[2].year)
        assertEquals(1, result.months[2].month)
    }

    @Test
    fun `monthly income and obligation counted every month`() {
        val result = ForecastHelper.build(inputs())
        result.months.forEach { month ->
            assertEquals(50000.0, month.totalIncome, 0.01)
            assertEquals(10000.0, month.mandatoryExpense, 0.01)
            assertEquals(40000.0, month.net, 0.01)
        }
        assertEquals(130000.0, result.projectedEndBalance, 0.01)
    }

    @Test
    fun `yearly obligation only in due month`() {
        val result = ForecastHelper.build(
            inputs(obligations = listOf(obligation(amount = 12000.0, periodType = "yearly", dueMonth = 12))),
        )
        assertEquals(0.0, result.months[0].mandatoryExpense, 0.01)
        assertEquals(12000.0, result.months[1].mandatoryExpense, 0.01)
        assertEquals(0.0, result.months[2].mandatoryExpense, 0.01)
    }

    @Test
    fun `yearly income only in due month`() {
        val result = ForecastHelper.build(
            inputs(
                horizon = ForecastHelper.Horizon.HALF_YEAR,
                incomeSources = listOf(income(amount = 60000.0, periodType = "yearly", dueMonth = 12)),
            ),
        )
        assertEquals(0.0, result.months[0].totalIncome, 0.01)
        assertEquals(60000.0, result.months[1].totalIncome, 0.01)
        assertEquals(0.0, result.months[5].totalIncome, 0.01)
    }

    @Test
    fun `monthly plan overrides obligation`() {
        val result = ForecastHelper.build(
            inputs(monthlyPlans = listOf(plan(2026, 11, 10, 8000.0))),
        )
        val first = result.months[0]
        assertEquals(8000.0, first.mandatoryExpense, 0.01)
        val line = first.expenseLines.first { it.categoryId == 10 }
        assertEquals(ForecastHelper.ExpenseSource.PLAN, line.source)
        val second = result.months[1]
        assertEquals(10000.0, second.mandatoryExpense, 0.01)
        assertEquals(ForecastHelper.ExpenseSource.OBLIGATION, second.expenseLines.first { it.categoryId == 10 }.source)
    }

    @Test
    fun `variable expense uses average fact and excluded for goal categories`() {
        val past = listOf(
            tx(20, 3000.0, "expense", LocalDate.of(2026, 7, 10)),
            tx(20, 4500.0, "expense", LocalDate.of(2026, 8, 10)),
            tx(20, 4500.0, "expense", LocalDate.of(2026, 9, 10)),
            tx(30, 1000.0, "expense", LocalDate.of(2026, 8, 10)),
        )
        val goals = listOf(goal())
        val result = ForecastHelper.build(inputs(pastTransactions = past, goals = goals))
        val line = result.months[0].expenseLines.first { it.categoryId == 20 }
        assertEquals(4000.0, line.amount, 0.01)
        assertEquals(ForecastHelper.ExpenseSource.AVG_FACT, line.source)
        assertTrue(result.months[0].expenseLines.none { it.categoryId == 30 && it.source == ForecastHelper.ExpenseSource.AVG_FACT })
    }

    @Test
    fun `optimistic scenario keeps planned income`() {
        val past = listOf(tx(1, 40000.0, "income", LocalDate.of(2026, 9, 5)))
        val result = ForecastHelper.build(inputs(scenario = ForecastHelper.Scenario.OPTIMISTIC, pastTransactions = past))
        assertEquals(50000.0, result.months[0].totalIncome, 0.01)
    }

    @Test
    fun `realistic scenario caps income by average fact`() {
        val past = listOf(
            tx(1, 40000.0, "income", LocalDate.of(2026, 7, 5)),
            tx(1, 40000.0, "income", LocalDate.of(2026, 8, 5)),
            tx(1, 40000.0, "income", LocalDate.of(2026, 9, 5)),
        )
        val result = ForecastHelper.build(inputs(scenario = ForecastHelper.Scenario.REALISTIC, pastTransactions = past))
        assertEquals(40000.0, result.months[0].totalIncome, 0.01)
        val variable = result.months[0].variableExpense
        assertEquals(0.0, variable, 0.01)
    }

    @Test
    fun `realistic scenario adds buffer to variable expenses`() {
        val past = listOf(
            tx(20, 3000.0, "expense", LocalDate.of(2026, 7, 10)),
            tx(20, 3000.0, "expense", LocalDate.of(2026, 8, 10)),
            tx(20, 3000.0, "expense", LocalDate.of(2026, 9, 10)),
        )
        val result = ForecastHelper.build(
            inputs(scenario = ForecastHelper.Scenario.REALISTIC, pastTransactions = past, obligations = emptyList()),
        )
        assertEquals(3300.0, result.months[0].variableExpense, 0.01)
    }

    @Test
    fun `goal contribution spread until deadline and counted once`() {
        val result = ForecastHelper.build(
            inputs(goals = listOf(goal(target = 60000.0)), goalBalances = mapOf(30 to 30000.0)),
        )
        val first = result.months[0]
        assertEquals(1, first.expenseLines.count { it.source == ForecastHelper.ExpenseSource.GOAL })
        val goalLine = first.expenseLines.first { it.source == ForecastHelper.ExpenseSource.GOAL }
        assertEquals(15000.0, goalLine.amount, 0.01)
        assertEquals(15000.0, first.goalsExpense, 0.01)
    }

    @Test
    fun `goal obligation subtraction prevents double counting`() {
        val goals = listOf(goal(target = 60000.0))
        val obligations = listOf(obligation(categoryId = 30, amount = 4000.0))
        val result = ForecastHelper.build(inputs(obligations = obligations, goals = goals))
        val first = result.months[0]
        assertEquals(4000.0, first.mandatoryExpense, 0.01)
        assertEquals(26000.0, first.goalsExpense, 0.01)
        assertEquals(30000.0, first.totalExpense, 0.01)
    }

    @Test
    fun `completed and archived goals are skipped`() {
        val goals = listOf(
            goal(id = 1, target = 10000.0),
            goal(id = 2, target = 10000.0, isActive = false),
        )
        val result = ForecastHelper.build(
            inputs(goals = goals, goalBalances = mapOf(30 to 10000.0)),
        )
        assertEquals(0.0, result.months[0].goalsExpense, 0.01)
    }

    @Test
    fun `goal without deadline is skipped`() {
        val goals = listOf(goal(deadline = ""))
        val result = ForecastHelper.build(inputs(goals = goals))
        assertEquals(0.0, result.months[0].goalsExpense, 0.01)
    }

    @Test
    fun `balances chain across months`() {
        val result = ForecastHelper.build(inputs())
        assertEquals(50000.0, result.months[0].projectedEndBalance, 0.01)
        assertEquals(90000.0, result.months[1].projectedEndBalance, 0.01)
        assertEquals(130000.0, result.months[2].projectedEndBalance, 0.01)
    }

    @Test
    fun `year horizon produces twelve months`() {
        val result = ForecastHelper.build(inputs(horizon = ForecastHelper.Horizon.YEAR))
        assertEquals(12, result.months.size)
        assertEquals(490000.0, result.projectedEndBalance, 0.01)
    }
}
