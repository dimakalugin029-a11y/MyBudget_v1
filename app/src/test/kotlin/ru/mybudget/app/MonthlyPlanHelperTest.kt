package ru.mybudget.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mybudget.app.data.MonthlyCategoryPlanEntity

class MonthlyPlanHelperTest {

    private fun category(
        plannedAmount: Double = 0.0,
        defaultPlannedAmount: Double = 0.0,
    ) = BudgetCategory(
        id = 1,
        name = "Test",
        parentId = 0,
        budgetId = 1,
        plannedAmount = plannedAmount,
        currentBalance = 0.0,
        defaultIncomeAmount = 0.0,
        defaultPlannedAmount = defaultPlannedAmount,
        isActive = true,
        position = 0,
        colorHex = "",
    )

    private fun plan(
        plannedAmount: Double = 0.0,
        isEnabled: Boolean = true,
    ) = MonthlyCategoryPlanEntity(
        year = 2026,
        month = 9,
        categoryId = 1,
        budgetId = 1,
        plannedAmount = plannedAmount,
        isEnabled = isEnabled,
    )

    @Test
    fun `isIncludedInPlan falls back to category planned amount without monthly plan`() {
        assertTrue(MonthlyPlanHelper.isIncludedInPlan(category(plannedAmount = 100.0), null))
        assertFalse(MonthlyPlanHelper.isIncludedInPlan(category(), null))
    }

    @Test
    fun `isIncludedInPlan respects monthly plan enabled flag`() {
        assertTrue(MonthlyPlanHelper.isIncludedInPlan(category(), plan(isEnabled = true)))
        assertFalse(MonthlyPlanHelper.isIncludedInPlan(category(plannedAmount = 100.0), plan(isEnabled = false)))
    }

    @Test
    fun `suggestedAmount prefers monthly plan then default then category`() {
        assertEquals(300.0, MonthlyPlanHelper.suggestedAmount(category(plannedAmount = 100.0, defaultPlannedAmount = 200.0), plan(300.0)), 1e-9)
        assertEquals(200.0, MonthlyPlanHelper.suggestedAmount(category(plannedAmount = 100.0, defaultPlannedAmount = 200.0), null), 1e-9)
        assertEquals(100.0, MonthlyPlanHelper.suggestedAmount(category(plannedAmount = 100.0), null), 1e-9)
        assertEquals(0.0, MonthlyPlanHelper.suggestedAmount(category(), null), 1e-9)
        assertEquals(0.0, MonthlyPlanHelper.suggestedAmount(category(), plan(0.0)), 1e-9)
    }

    @Test
    fun `effectivePlannedAmount is zero when monthly plan disabled`() {
        assertEquals(0.0, MonthlyPlanHelper.effectivePlannedAmount(category(plannedAmount = 100.0), plan(isEnabled = false)), 1e-9)
    }

    @Test
    fun `effectivePlannedAmount uses monthly plan amount when positive`() {
        assertEquals(250.0, MonthlyPlanHelper.effectivePlannedAmount(category(plannedAmount = 100.0), plan(250.0)), 1e-9)
    }

    @Test
    fun `effectivePlannedAmount falls back to category amount`() {
        assertEquals(100.0, MonthlyPlanHelper.effectivePlannedAmount(category(plannedAmount = 100.0), plan(0.0)), 1e-9)
        assertEquals(100.0, MonthlyPlanHelper.effectivePlannedAmount(category(plannedAmount = 100.0), null), 1e-9)
        assertEquals(0.0, MonthlyPlanHelper.effectivePlannedAmount(category(), null), 1e-9)
    }

    @Test
    fun `shiftMonth handles year boundaries both directions`() {
        assertEquals(MonthlyPlanHelper.MonthKey(2026, 1), MonthlyPlanHelper.shiftMonth(2025, 12, 1))
        assertEquals(MonthlyPlanHelper.MonthKey(2025, 12), MonthlyPlanHelper.shiftMonth(2026, 1, -1))
        assertEquals(MonthlyPlanHelper.MonthKey(2026, 3), MonthlyPlanHelper.shiftMonth(2026, 1, 2))
    }

    @Test
    fun `shiftMonth is safe from any day since it anchors to day one`() {
        assertEquals(MonthlyPlanHelper.MonthKey(2026, 3), MonthlyPlanHelper.shiftMonth(2026, 2, 1))
    }

    @Test
    fun `isFutureMonth compares against current month`() {
        val now = MonthlyPlanHelper.currentMonth()
        assertTrue(MonthlyPlanHelper.isFutureMonth(now.year + 1, now.month))
        assertFalse(MonthlyPlanHelper.isFutureMonth(now.year - 1, now.month))
        assertFalse(MonthlyPlanHelper.isFutureMonth(now.year, now.month))
    }
}
