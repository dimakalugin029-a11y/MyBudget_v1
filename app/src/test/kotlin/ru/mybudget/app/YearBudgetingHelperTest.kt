package ru.mybudget.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mybudget.app.data.MonthlyCategoryPlanEntity
import ru.mybudget.app.data.MonthlyIncomePlanEntity
import ru.mybudget.app.data.PlannedIncomeSourceEntity
import ru.mybudget.app.data.PlannedObligationEntity
import ru.mybudget.app.data.TransactionEntity

class YearBudgetingHelperTest {

    private fun source(
        id: Int,
        name: String = "Source$id",
        amount: Double = 50000.0,
        sourceType: String = PlannedIncomeHelper.TYPE_SALARY,
        periodType: String = PlannedIncomeHelper.PERIOD_MONTHLY,
        isActive: Boolean = true,
    ) = PlannedIncomeSourceEntity(
        id = id,
        budgetId = 1,
        name = name,
        amount = amount,
        sourceType = sourceType,
        periodType = periodType,
        isActive = isActive,
    )

    private fun category(
        id: Int,
        name: String = "Cat$id",
        plannedAmount: Double = 10000.0,
        defaultPlannedAmount: Double = 0.0,
        isActive: Boolean = true,
    ) = BudgetCategory(
        id = id,
        name = name,
        parentId = 0,
        budgetId = 1,
        plannedAmount = plannedAmount,
        defaultPlannedAmount = defaultPlannedAmount,
        isActive = isActive,
    )

    private fun incomeOverride(
        month: Int,
        sourceId: Int,
        amount: Double,
        isEnabled: Boolean = true,
    ) = MonthlyIncomePlanEntity(year = 2026, month = month, sourceId = sourceId, budgetId = 1, amount = amount, isEnabled = isEnabled)

    @Test
    fun incomeUsesMonthlyEquivalentByDefault() {
        val bonus = source(2, amount = 30000.0, sourceType = PlannedIncomeHelper.TYPE_BONUS, periodType = PlannedIncomeHelper.PERIOD_QUARTERLY)
        val line = YearBudgetingHelper.incomeLineFor(bonus, emptyMap())!!
        assertEquals(10000.0, line.amount, 0.001)
        assertFalse(line.isOverride)
    }

    @Test
    fun incomeOverrideReplacesDefault() {
        val s = source(1, amount = 50000.0)
        val overrides = mapOf(1 to incomeOverride(3, 1, 60000.0))
        val line = YearBudgetingHelper.incomeLineFor(s, overrides)!!
        assertEquals(60000.0, line.amount, 0.001)
        assertTrue(line.isOverride)
    }

    @Test
    fun incomeDisabledOverrideExcludesLine() {
        val s = source(1, amount = 50000.0)
        val overrides = mapOf(1 to incomeOverride(3, 1, 60000.0, isEnabled = false))
        assertNull(YearBudgetingHelper.incomeLineFor(s, overrides))
    }

    @Test
    fun inactiveSourceExcluded() {
        assertNull(YearBudgetingHelper.incomeLineFor(source(1, isActive = false), emptyMap()))
    }

    @Test
    fun expenseUsesMonthlyPlanThenFallback() {
        val cat = category(10, plannedAmount = 7000.0)
        val withPlan = YearBudgetingHelper.expenseLineFor(
            cat,
            MonthlyCategoryPlanEntity(2026, 1, 10, 1, plannedAmount = 9000.0),
        )!!
        assertEquals(9000.0, withPlan.amount, 0.001)
        val fallback = YearBudgetingHelper.expenseLineFor(cat, null)!!
        assertEquals(7000.0, fallback.amount, 0.001)
    }

    @Test
    fun expenseFallsBackToObligationsWhenNoPlan() {
        val cat = category(10, plannedAmount = 0.0)
        val obligation = PlannedObligationEntity(
            id = 1,
            budgetId = 1,
            name = "Кредит",
            amount = 24000.0,
            periodType = PlannedObligationHelper.PERIOD_MONTHLY,
            categoryId = 10,
            paychecksPerMonth = 1,
            dueMonth = 1,
            note = "",
            isActive = true,
            createdAt = 0L,
        )
        val line = YearBudgetingHelper.expenseLineFor(cat, null, 24000.0)!!
        assertEquals(24000.0, line.amount, 0.001)
        val month = YearBudgetingHelper.buildMonth(
            year = 2026,
            month = 1,
            sources = emptyList(),
            expenseCategories = listOf(cat),
            categoryPlans = emptyList(),
            incomePlanOverrides = emptyList(),
            obligations = listOf(obligation),
            fact = null,
        )
        assertEquals(24000.0, month.expenseTotal, 0.001)
    }

    @Test
    fun yearlyObligationSpreadAcrossMonths() {
        val cat = category(10, plannedAmount = 0.0)
        val obligation = PlannedObligationEntity(
            id = 1,
            budgetId = 1,
            name = "Налог",
            amount = 12000.0,
            periodType = PlannedObligationHelper.PERIOD_YEARLY,
            categoryId = 10,
            paychecksPerMonth = 1,
            dueMonth = 1,
            note = "",
            isActive = true,
            createdAt = 0L,
        )
        val plan = YearBudgetingHelper.buildYear(
            year = 2026,
            sources = emptyList(),
            expenseCategories = listOf(cat),
            categoryPlansByMonth = emptyMap(),
            incomeOverridesByMonth = emptyMap(),
            obligations = listOf(obligation),
            factByMonth = emptyMap(),
        )
        assertEquals(1000.0, plan.months[0].expenseTotal, 0.001)
        assertEquals(12000.0, plan.expenseTotal, 0.001)
    }

    @Test
    fun expenseDisabledPlanMeansZero() {
        val cat = category(10, plannedAmount = 10000.0)
        val plan = MonthlyCategoryPlanEntity(2026, 1, 10, 1, plannedAmount = 9000.0, isEnabled = false)
        assertNull(YearBudgetingHelper.expenseLineFor(cat, plan))
    }

    @Test
    fun inactiveCategoryExcluded() {
        assertNull(YearBudgetingHelper.expenseLineFor(category(10, isActive = false), null))
    }

    @Test
    fun buildMonthComputesTotalsAndNet() {
        val month = YearBudgetingHelper.buildMonth(
            year = 2026,
            month = 1,
            sources = listOf(source(1), source(2, amount = 20000.0)),
            expenseCategories = listOf(category(10, plannedAmount = 30000.0), category(11, plannedAmount = 5000.0)),
            categoryPlans = emptyList(),
            incomePlanOverrides = emptyList(),
            fact = null,
        )
        assertEquals(70000.0, month.incomeTotal, 0.001)
        assertEquals(35000.0, month.expenseTotal, 0.001)
        assertEquals(35000.0, month.net, 0.001)
        assertNull(month.incomeDelta)
    }

    @Test
    fun buildMonthWithFactComputesDeltas() {
        val month = YearBudgetingHelper.buildMonth(
            year = 2026,
            month = 1,
            sources = listOf(source(1, amount = 50000.0)),
            expenseCategories = listOf(category(10, plannedAmount = 30000.0)),
            categoryPlans = emptyList(),
            incomePlanOverrides = emptyList(),
            fact = YearBudgetingHelper.MonthFact(income = 55000.0, expense = 25000.0),
        )
        val incomeDelta = month.incomeDelta!!
        val expenseDelta = month.expenseDelta!!
        val netDelta = month.netDelta!!
        assertEquals(5000.0, incomeDelta.absolute, 0.001)
        assertEquals(10.0, incomeDelta.percent!!, 0.001)
        assertEquals(-5000.0, expenseDelta.absolute, 0.001)
        assertEquals(10000.0, netDelta.absolute, 0.001)
    }

    @Test
    fun buildYearAggregatesQuartersAndYear() {
        val plan = YearBudgetingHelper.buildYear(
            year = 2026,
            sources = listOf(source(1, amount = 60000.0)),
            expenseCategories = listOf(category(10, plannedAmount = 40000.0)),
            categoryPlansByMonth = emptyMap(),
            incomeOverridesByMonth = mapOf(6 to listOf(incomeOverride(6, 1, 120000.0))),
            factByMonth = emptyMap(),
        )
        assertEquals(12, plan.months.size)
        assertEquals(60000.0, plan.months[0].incomeTotal, 0.001)
        assertEquals(120000.0, plan.months[5].incomeTotal, 0.001)
        assertEquals(40000.0, plan.months[0].expenseTotal, 0.001)
        assertEquals(20000.0, plan.months[0].net, 0.001)
        assertEquals(4, plan.quarters.size)
        assertEquals(180000.0, plan.quarters[0].incomeTotal, 0.001)
        assertEquals(120000.0, plan.quarters[0].expenseTotal, 0.001)
        assertEquals(240000.0, plan.quarters[1].incomeTotal, 0.001)
        val yearIncome = 60000.0 * 11 + 120000.0
        assertEquals(yearIncome, plan.incomeTotal, 0.001)
        assertEquals(480000.0, plan.expenseTotal, 0.001)
        assertEquals(yearIncome - 480000.0, plan.net, 0.001)
    }

    @Test
    fun factTotalsFiltersTypes() {
        val txs = listOf(
            tx(1, 10000.0, "income"),
            tx(2, 4000.0, "expense"),
            tx(3, 100.0, "transfer"),
        )
        val fact = YearBudgetingHelper.factTotals(txs)
        assertEquals(10000.0, fact.income, 0.001)
        assertEquals(4000.0, fact.expense, 0.001)
    }

    @Test
    fun expenseFactByCategoryProportional() {
        val month = YearBudgetingHelper.buildMonth(
            year = 2026,
            month = 1,
            sources = emptyList(),
            expenseCategories = listOf(category(10, plannedAmount = 30000.0), category(11, plannedAmount = 10000.0)),
            categoryPlans = emptyList(),
            incomePlanOverrides = emptyList(),
            fact = YearBudgetingHelper.MonthFact(0.0, 20000.0),
        )
        val byCat = month.expenseFactByCategory()
        assertEquals(15000.0, byCat[10]!!, 0.001)
        assertEquals(5000.0, byCat[11]!!, 0.001)
    }

    @Test
    fun isPastOrCurrentMonthBoundary() {
        assertTrue(YearBudgetingHelper.isPastOrCurrentMonth(2026, 10, 2026, 10))
        assertFalse(YearBudgetingHelper.isPastOrCurrentMonth(2026, 11, 2026, 10))
        assertTrue(YearBudgetingHelper.isPastOrCurrentMonth(2025, 12, 2026, 10))
    }

    @Test
    fun closedMonthFixationIgnoresLaterObligationChanges() {
        val cat = category(10, plannedAmount = 0.0)
        val fixation = YearBudgetingHelper.expenseFixations(
            year = 2026,
            month = 9,
            budgetId = 1,
            expenseCategories = listOf(cat),
            categoryPlans = emptyList(),
            obligations = listOf(obligationFor(10, 10000.0)),
        )
        assertEquals(1, fixation.size)
        assertEquals(10000.0, fixation[0].plannedAmount, 0.001)
        assertTrue(fixation[0].isEnabled)

        val raised = YearBudgetingHelper.buildMonth(
            year = 2026,
            month = 9,
            sources = emptyList(),
            expenseCategories = listOf(cat),
            categoryPlans = fixation,
            incomePlanOverrides = emptyList(),
            obligations = listOf(obligationFor(10, 20000.0)),
            fact = null,
        )
        assertEquals(10000.0, raised.expenseTotal, 0.001)
    }

    @Test
    fun fixationKeepsZeroRowsSoMonthStaysFrozen() {
        val cat = category(10, plannedAmount = 0.0)
        val fixation = YearBudgetingHelper.expenseFixations(
            year = 2026,
            month = 8,
            budgetId = 1,
            expenseCategories = listOf(cat),
            categoryPlans = emptyList(),
            obligations = emptyList(),
        )
        assertFalse(fixation[0].isEnabled)
        assertEquals(0.0, fixation[0].plannedAmount, 0.001)
        val afterNewObligation = YearBudgetingHelper.buildMonth(
            year = 2026,
            month = 8,
            sources = emptyList(),
            expenseCategories = listOf(cat),
            categoryPlans = fixation,
            incomePlanOverrides = emptyList(),
            obligations = listOf(obligationFor(10, 5000.0)),
            fact = null,
        )
        assertEquals(0.0, afterNewObligation.expenseTotal, 0.001)
    }

    @Test
    fun isClosedMonthBoundary() {
        assertTrue(YearBudgetingHelper.isClosedMonth(2026, 9, 2026, 10))
        assertFalse(YearBudgetingHelper.isClosedMonth(2026, 10, 2026, 10))
        assertFalse(YearBudgetingHelper.isClosedMonth(2026, 11, 2026, 10))
        assertTrue(YearBudgetingHelper.isClosedMonth(2025, 12, 2026, 1))
    }

    @Test
    fun categoryYearSummariesMergePlanAndFact() {
        val months = listOf(
            YearBudgetingHelper.buildMonth(
                year = 2026, month = 1,
                sources = emptyList(),
                expenseCategories = listOf(category(10, plannedAmount = 30000.0), category(11, plannedAmount = 5000.0)),
                categoryPlans = emptyList(), incomePlanOverrides = emptyList(), fact = null,
            ),
            YearBudgetingHelper.buildMonth(
                year = 2026, month = 2,
                sources = emptyList(),
                expenseCategories = listOf(category(10, plannedAmount = 30000.0)),
                categoryPlans = emptyList(), incomePlanOverrides = emptyList(), fact = null,
            ),
        )
        val summaries = YearBudgetingHelper.categoryYearSummaries(
            months = months,
            factByCategory = mapOf(10 to 45000.0, 12 to 1000.0),
            nameById = mapOf(10 to "Жильё", 11 to "Связь", 12 to "Прочее"),
        )
        assertEquals(3, summaries.size)
        val housing = summaries.first { it.categoryId == 10 }
        assertEquals(60000.0, housing.planTotal, 0.001)
        assertEquals(45000.0, housing.factTotal, 0.001)
        assertEquals(15000.0, housing.remaining, 0.001)
        assertEquals(1000.0, summaries.first { it.categoryId == 12 }.factTotal, 0.001)
        assertEquals("Жильё", summaries[0].name)
    }

    private fun obligationFor(categoryId: Int, amount: Double) = PlannedObligationEntity(
        id = 1,
        budgetId = 1,
        name = "Obligation",
        amount = amount,
        periodType = PlannedObligationHelper.PERIOD_MONTHLY,
        categoryId = categoryId,
        paychecksPerMonth = 1,
        dueMonth = 1,
        isActive = true,
    )

    private fun tx(
        categoryId: Int,
        amount: Double,
        type: String,
        dateMs: Long = 1_800_000_000_000L,
    ) = TransactionEntity(
        id = 0,
        categoryId = categoryId,
        amount = amount,
        type = type,
        description = "",
        date = dateMs,
        groupId = null,
        participantLabel = "",
    )
}
