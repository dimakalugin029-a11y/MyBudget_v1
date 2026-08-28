package ru.mybudget.app

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.mybudget.app.data.PlannedObligationEntity

class PlannedObligationHelperTest {
    @Test
    fun distributionByCategory_sumsMultipleObligationsOnSameCategory() {
        val obligations = listOf(
            obligation(name = "Интернет", categoryId = 10, amount = 600.0, paychecks = 2),
            obligation(name = "Телефон", categoryId = 10, amount = 400.0, paychecks = 2),
            obligation(name = "Кредит", categoryId = 20, amount = 10_000.0, paychecks = 1),
        )
        val totals = PlannedObligationHelper.distributionByCategory(obligations)
        assertEquals(500.0, totals[10]!!, 0.001)
        assertEquals(10_000.0, totals[20]!!, 0.001)
        assertEquals(2, PlannedObligationHelper.breakdownByCategory(obligations)[10]?.size)
    }

    @Test
    fun distributionByCategory_ignoresInactiveAndUnlinked() {
        val obligations = listOf(
            obligation(name = "Связь", categoryId = 10, amount = 300.0, paychecks = 1, isActive = false),
            obligation(name = "Налог", categoryId = 0, amount = 1000.0, paychecks = 1),
        )
        assertEquals(emptyMap<Int, Double>(), PlannedObligationHelper.distributionByCategory(obligations))
    }

    private fun obligation(
        name: String,
        categoryId: Int,
        amount: Double,
        paychecks: Int,
        isActive: Boolean = true,
    ) = PlannedObligationEntity(
        budgetId = 1,
        name = name,
        amount = amount,
        periodType = PlannedObligationHelper.PERIOD_MONTHLY,
        categoryId = categoryId,
        paychecksPerMonth = paychecks,
        dueMonth = 1,
        dueDay = 10,
        isActive = isActive,
    )
}
