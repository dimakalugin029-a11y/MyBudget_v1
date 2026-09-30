package ru.mybudget.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BudgetPlanHelperTest {

    private fun category(
        plannedAmount: Double = 0.0,
        currentBalance: Double = 0.0,
    ) = BudgetCategory(
        id = 1,
        name = "Test",
        parentId = 0,
        budgetId = 1,
        plannedAmount = plannedAmount,
        currentBalance = currentBalance,
        defaultIncomeAmount = 0.0,
        defaultPlannedAmount = 0.0,
        isActive = true,
        position = 0,
        colorHex = "",
    )

    @Test
    fun `planPercent is zero for non positive plan`() {
        assertEquals(0, BudgetPlanHelper.planPercent(100.0, 0.0))
        assertEquals(0, BudgetPlanHelper.planPercent(100.0, -5.0))
    }

    @Test
    fun `planPercent truncates and clamps to 0-100`() {
        assertEquals(50, BudgetPlanHelper.planPercent(50.0, 100.0))
        assertEquals(99, BudgetPlanHelper.planPercent(99.9, 100.0))
        assertEquals(100, BudgetPlanHelper.planPercent(150.0, 100.0))
        assertEquals(0, BudgetPlanHelper.planPercent(-10.0, 100.0))
    }

    @Test
    fun `isOverspent respects threshold and zero plan`() {
        assertTrue(BudgetPlanHelper.isOverspent(90.0, 100.0, 90))
        assertFalse(BudgetPlanHelper.isOverspent(89.0, 100.0, 90))
        assertFalse(BudgetPlanHelper.isOverspent(1000.0, 0.0, 90))
    }

    @Test
    fun `isCategoryOverspent triggers on negative balance`() {
        assertTrue(
            BudgetPlanHelper.isCategoryOverspent(
                category(currentBalance = -1.0),
                spent = 0.0,
                thresholdPercent = 100,
            )
        )
        assertFalse(
            BudgetPlanHelper.isCategoryOverspent(
                category(plannedAmount = 100.0),
                spent = 50.0,
                thresholdPercent = 90,
            )
        )
    }

    @Test
    fun `isCategoryOverspent uses plannedAmount override`() {
        assertTrue(
            BudgetPlanHelper.isCategoryOverspent(
                category(plannedAmount = 100.0),
                spent = 150.0,
                thresholdPercent = 90,
                plannedAmount = 150.0,
            )
        )
    }

    @Test
    fun `matchesFilter returns all categories for ALL`() {
        assertTrue(
            BudgetPlanHelper.matchesFilter(category(), 0.0, BudgetPlanHelper.ListFilter.ALL, 100)
        )
    }

    @Test
    fun `matchesFilter NON_ZERO requires nonzero balance or spent`() {
        val filter = BudgetPlanHelper.ListFilter.NON_ZERO
        assertTrue(BudgetPlanHelper.matchesFilter(category(currentBalance = 1.0), 0.0, filter, 100))
        assertTrue(BudgetPlanHelper.matchesFilter(category(), 5.0, filter, 100))
        assertFalse(BudgetPlanHelper.matchesFilter(category(), 0.0, filter, 100))
    }

    @Test
    fun `matchesFilter OVERSPEND uses category overspend check`() {
        val filter = BudgetPlanHelper.ListFilter.OVERSPEND
        assertTrue(
            BudgetPlanHelper.matchesFilter(category(plannedAmount = 100.0), 100.0, filter, 100)
        )
        assertFalse(
            BudgetPlanHelper.matchesFilter(category(plannedAmount = 100.0), 50.0, filter, 100)
        )
    }

    @Test
    fun `safeToSpendDaily returns null for zero balance`() {
        assertNull(BudgetPlanHelper.safeToSpendDaily(0.0))
        assertNull(BudgetPlanHelper.safeToSpendDaily(-100.0))
    }

    @Test
    fun `safeToSpendDaily with reservation returns null when nothing left`() {
        assertNull(BudgetPlanHelper.safeToSpendDaily(100.0, 100.0))
        assertNull(BudgetPlanHelper.safeToSpendDaily(100.0, 200.0))
    }

    @Test
    fun `safeToSpendDaily with reservation divides remaining amount`() {
        val daysLeft = BudgetPlanHelper.daysLeftInMonth()
        assertEquals((100.0 - 40.0) / daysLeft, BudgetPlanHelper.safeToSpendDaily(100.0, 40.0)!!, 1e-9)
    }
}
