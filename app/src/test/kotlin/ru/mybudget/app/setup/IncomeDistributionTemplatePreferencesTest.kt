package ru.mybudget.app.setup

import org.junit.Assert.assertEquals
import org.junit.Test

class IncomeDistributionTemplatePreferencesTest {
    @Test
    fun scaledAmounts_scalesToNewTotalIncome() {
        val template = IncomeDistributionTemplatePreferences.Template(
            budgetId = 1,
            categoryIds = intArrayOf(10, 20),
            amounts = doubleArrayOf(60_000.0, 40_000.0),
            savedTotalIncome = 100_000.0,
        )

        val scaled = IncomeDistributionTemplatePreferences.scaledAmounts(template, 80_000.0)

        assertEquals(48_000.0, scaled[10]!!, 0.01)
        assertEquals(32_000.0, scaled[20]!!, 0.01)
    }
}
