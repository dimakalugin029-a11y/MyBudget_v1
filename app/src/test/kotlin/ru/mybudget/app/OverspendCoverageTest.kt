package ru.mybudget.app

import org.junit.Assert.*
import org.junit.Test

class OverspendCoverageTest {
    private fun category(id: Int, balance: Double, parent: Int = 0, budget: Int = 1, active: Boolean = true) =
        BudgetCategory(id, "Category $id", parent, budgetId = budget, currentBalance = balance, isActive = active)

    @Test fun showsLeavesAndStandaloneRootsWithoutDoubleCountingParent() {
        val rows = listOf(category(1, -100.0), category(2, -100.0, parent = 1), category(3, -20.0))
        assertEquals(listOf(2, 3), OverspendCoverage.candidates(rows, 1).map { it.id })
    }
    @Test fun excludesArchivedOtherBudgetsAndNonnegativeBalances() {
        val rows = listOf(category(1, -10.0, active = false), category(2, -10.0, budget = 2),
            category(3, 0.0), category(4, 100.0), category(5, -10.0))
        assertEquals(listOf(5), OverspendCoverage.candidates(rows, 1).map { it.id })
    }
    @Test fun ignoresInvalidAndSubcentBalances() {
        val rows = listOf(category(1, Double.NaN), category(2, Double.NEGATIVE_INFINITY), category(3, -0.001))
        assertTrue(OverspendCoverage.candidates(rows, 1).isEmpty())
    }
    @Test fun allowsPartialAndExactCoverageButRejectsOverfillAndInvalidNumbers() {
        assertTrue(OverspendCoverage.valid(40.0, 100.0))
        assertTrue(OverspendCoverage.valid(100.0, 100.0))
        assertFalse(OverspendCoverage.valid(100.01, 100.0))
        assertFalse(OverspendCoverage.valid(-1.0, 100.0))
        assertFalse(OverspendCoverage.valid(Double.NaN, 100.0))
        assertFalse(OverspendCoverage.valid(Double.POSITIVE_INFINITY, 100.0))
        assertFalse(OverspendCoverage.valid(null, 100.0))
    }
    @Test fun otherAllocationsConsumeTheSameIncome() {
        assertTrue(OverspendCoverage.fits(100.0, 30.0, 70.0))
        assertFalse(OverspendCoverage.fits(100.0, 30.0, 70.01))
        assertFalse(OverspendCoverage.fits(100.0, 110.0, 0.0))
    }
    @Test fun centArithmeticDoesNotRejectFloatingPointRemainders() {
        assertTrue(OverspendCoverage.fits(0.3, 0.1, 0.2))
        assertEquals(12.34, OverspendCoverage.deficit(category(1, -12.34)), 0.0)
    }
    @Test fun archivedChildDoesNotHideActiveStandaloneRoot() {
        val rows = listOf(category(1, -10.0), category(2, -20.0, parent = 1, active = false))
        assertEquals(listOf(1), OverspendCoverage.candidates(rows, 1).map { it.id })
    }
    @Test fun invalidIncomeNeverCreatesCapacity() {
        assertFalse(OverspendCoverage.fits(Double.NaN, 0.0, 1.0))
        assertFalse(OverspendCoverage.fits(Double.POSITIVE_INFINITY, 0.0, 1.0))
        assertFalse(OverspendCoverage.fits(0.0, 0.0, 0.0))
        assertFalse(OverspendCoverage.fits(10.0, -1.0, 1.0))
    }
}
