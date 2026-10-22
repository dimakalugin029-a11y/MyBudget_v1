package ru.mybudget.app

import org.junit.Assert.*
import org.junit.Test

class BudgetListHelperTest {
    private fun category(id: Int, parent: Int = 0, balance: Double = 0.0, plan: Double = 0.0,
        active: Boolean = true, position: Int = id) = BudgetCategory(
        id = id, name = "Category $id", parentId = parent, currentBalance = balance,
        plannedAmount = plan, isActive = active, position = position,
    )

    @Test fun standaloneRootIsNotLostInActiveAndOverspendFilters() {
        val categories = listOf(category(1, balance = -25.0), category(2))
        for (filter in listOf(BudgetPlanHelper.ListFilter.NON_ZERO, BudgetPlanHelper.ListFilter.OVERSPEND)) {
            val result = BudgetListHelper.visibleGroups(categories, filter, emptyMap(), emptyMap(), 100)
            assertEquals(listOf(1), result.map { it.parent.id })
            assertFalse(result.single().hasChildren)
        }
    }

    @Test fun filteringKeepsParentWithOnlyItsMatchingChildren() {
        val categories = listOf(category(1), category(2, 1), category(3, 1, balance = -10.0),
            category(4), category(5, 4))
        val result = BudgetListHelper.visibleGroups(categories, BudgetPlanHelper.ListFilter.OVERSPEND,
            emptyMap(), emptyMap(), 100)
        assertEquals(listOf(1), result.map { it.parent.id })
        assertEquals(listOf(3), result.single().children.map { it.id })
        assertTrue(result.single().hasChildren)
    }

    @Test fun disabledMonthlyPlanDoesNotFallBackToCategoryTemplate() {
        val result = BudgetListHelper.visibleGroups(listOf(category(1, balance = 20.0, plan = 100.0)),
            BudgetPlanHelper.ListFilter.OVERSPEND, mapOf(1 to 150.0), mapOf(1 to 0.0), 100)
        assertTrue(result.isEmpty())
    }

    @Test fun archivedChildDoesNotMakeStandaloneRootDisappear() {
        val result = BudgetListHelper.visibleGroups(listOf(category(1, balance = 10.0), category(2, 1, active = false)),
            BudgetPlanHelper.ListFilter.NON_ZERO, emptyMap(), emptyMap(), 100)
        assertEquals(1, result.single().parent.id)
        assertFalse(result.single().hasChildren)
    }

    @Test fun monthlyMovementKeepsZeroBalanceRootVisible() {
        val result = BudgetListHelper.visibleGroups(listOf(category(1)), BudgetPlanHelper.ListFilter.NON_ZERO,
            mapOf(1 to 40.0), emptyMap(), 100)
        assertEquals(1, result.single().parent.id)
    }
}
