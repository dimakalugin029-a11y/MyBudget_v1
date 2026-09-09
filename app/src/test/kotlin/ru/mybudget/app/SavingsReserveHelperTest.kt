package ru.mybudget.app

import org.junit.Assert.assertEquals
import org.junit.Test

class SavingsReserveHelperTest {
    private val category = BudgetCategory(id = 2, name = "Продукты", parentId = 1, budgetId = 1, currentBalance = 5000.0)
    private val candidate = RolloverCandidate(category, "Быт → Продукты", 5000.0)

    @Test
    fun computeLine_usesEconomyWhenPlanExists() {
        val line = SavingsReserveHelper.computeLine(
            candidate = candidate,
            planned = 15_000.0,
            spent = 12_000.0,
        )

        assertEquals(SavingsReserveHelper.Basis.ECONOMY, line.basis)
        assertEquals(3000.0, line.economy, 0.01)
    }

    @Test
    fun computeLine_fallsBackToRemainderWithoutPlan() {
        val line = SavingsReserveHelper.computeLine(
            candidate = candidate,
            planned = 0.0,
            spent = 1000.0,
        )

        assertEquals(SavingsReserveHelper.Basis.REMAINDER, line.basis)
        assertEquals(0.0, line.economy, 0.01)
    }

    @Test
    fun reserveAmount_capsEconomyByBalance() {
        val line = SavingsReserveHelper.computeLine(
            candidate = candidate.copy(balance = 2000.0),
            planned = 10_000.0,
            spent = 5000.0,
        )

        assertEquals(1000.0, SavingsReserveHelper.reserveAmount(line, 50), 0.01)
    }

    @Test
    fun reserveAmount_usesRemainderWhenNoPlan() {
        val line = SavingsReserveHelper.computeLine(
            candidate = candidate,
            planned = 0.0,
            spent = 0.0,
        )

        assertEquals(2500.0, SavingsReserveHelper.reserveAmount(line, 50), 0.01)
    }

    @Test
    fun totalReserve_sumsSelectedLines() {
        val other = RolloverCandidate(
            category.copy(id = 3, name = "Транспорт", currentBalance = 1000.0),
            "Быт → Транспорт",
            1000.0,
        )
        val lines = listOf(
            SavingsReserveHelper.computeLine(candidate, 0.0, 0.0),
            SavingsReserveHelper.computeLine(other, 0.0, 0.0),
        )

        val total = SavingsReserveHelper.totalReserve(lines, setOf(2, 3), 50)
        assertEquals(3000.0, total, 0.01)
    }
}
