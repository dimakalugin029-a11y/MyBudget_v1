package ru.mybudget.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mybudget.app.data.PlannedIncomeSourceEntity

class PlannedIncomeHelperTest {
    @Test
    fun monthlyTotal_sumsActiveSources() {
        val sources = listOf(
            PlannedIncomeSourceEntity(
                budgetId = 1,
                name = "Аванс",
                amount = 45_000.0,
                sourceType = PlannedIncomeHelper.TYPE_ADVANCE,
            ),
            PlannedIncomeSourceEntity(
                budgetId = 1,
                name = "Зарплата",
                amount = 80_000.0,
                sourceType = PlannedIncomeHelper.TYPE_SALARY,
            ),
            PlannedIncomeSourceEntity(
                budgetId = 1,
                name = "Старый",
                amount = 10_000.0,
                sourceType = PlannedIncomeHelper.TYPE_OTHER,
                isActive = false,
            ),
        )

        assertEquals(125_000.0, PlannedIncomeHelper.monthlyTotal(sources), 0.01)
    }

    @Test
    fun freeAfterObligations_subtractsMonthlyLoad() {
        assertEquals(78_000.0, PlannedIncomeHelper.freeAfterObligations(140_000.0, 62_000.0), 0.01)
    }

    @Test
    fun suggestionsForIncomeEntry_returnsSourcesNearToday() {
        val today = java.time.LocalDate.of(2026, 8, 28)
        val sources = listOf(
            source("Зарплата", 80_000.0, dayOfMonth = 10),
            source("Аванс", 45_000.0, dayOfMonth = 25),
            source("Далёкий", 5_000.0, dayOfMonth = 15),
        )

        val suggestions = PlannedIncomeHelper.suggestionsForIncomeEntry(sources, today, windowDays = 5)

        assertEquals(listOf("Аванс"), suggestions.map { it.name })
    }

    @Test
    fun occurrencesInHorizon_skipsFlexibleDaySources() {
        val today = java.time.LocalDate.of(2026, 8, 31).toEpochDay()
        val sources = listOf(
            source("Зарплата", 80_000.0, dayOfMonth = 10),
            source("Фриланс", 15_000.0, dayOfMonth = 0),
        )

        val occurrences = PlannedIncomeHelper.occurrencesInHorizon(sources, today, horizonDays = 60)

        assertEquals(2, occurrences.size)
        assertTrue(occurrences.all { it.source.name == "Зарплата" })
    }

    @Test
    fun balanceBySource_subtractsLinkedObligations() {
        val sources = listOf(
            PlannedIncomeSourceEntity(
                id = 1,
                budgetId = 1,
                name = "Зарплата",
                amount = 80_000.0,
                sourceType = PlannedIncomeHelper.TYPE_SALARY,
            ),
        )
        val obligations = listOf(
            ru.mybudget.app.data.PlannedObligationEntity(
                budgetId = 1,
                name = "Ипотека",
                amount = 30_000.0,
                periodType = PlannedObligationHelper.PERIOD_MONTHLY,
                categoryId = 1,
                paychecksPerMonth = 1,
                dueMonth = 1,
                linkedIncomeSourceId = 1,
            ),
        )

        val balances = PlannedIncomeHelper.balanceBySource(sources, obligations)

        assertEquals(1, balances.size)
        assertEquals(30_000.0, balances[0].linkedObligationsMonthly, 0.01)
        assertEquals(50_000.0, balances[0].freeAmount, 0.01)
    }

    private fun source(name: String, amount: Double, dayOfMonth: Int) = PlannedIncomeSourceEntity(
        budgetId = 1,
        name = name,
        amount = amount,
        sourceType = PlannedIncomeHelper.TYPE_SALARY,
        dayOfMonth = dayOfMonth,
    )
}
