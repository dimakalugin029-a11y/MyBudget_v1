package ru.mybudget.app

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.mybudget.app.data.PlannedObligationEntity

class PlannedObligationKindTest {
    @Test
    fun filterByKind_returnsOnlyMatchingItems() {
        val items = listOf(
            obligation("Кредит", PlannedObligationHelper.KIND_CREDIT),
            obligation("Интернет", PlannedObligationHelper.KIND_SUBSCRIPTION),
            obligation("Ипотека", PlannedObligationHelper.KIND_CREDIT),
        )

        val credits = PlannedObligationHelper.filterByKind(items, PlannedObligationHelper.KIND_CREDIT)

        assertEquals(2, credits.size)
        assertEquals(listOf("Кредит", "Ипотека"), credits.map { it.name })
    }

    @Test
    fun normalizeKind_unknownDefaultsToOther() {
        assertEquals(PlannedObligationHelper.KIND_OTHER, PlannedObligationHelper.normalizeKind(null))
        assertEquals(PlannedObligationHelper.KIND_OTHER, PlannedObligationHelper.normalizeKind("legacy"))
    }

    private fun obligation(name: String, kind: String) = PlannedObligationEntity(
        budgetId = 1,
        name = name,
        amount = 1_000.0,
        periodType = PlannedObligationHelper.PERIOD_MONTHLY,
        categoryId = 1,
        paychecksPerMonth = 1,
        dueMonth = 1,
        obligationKind = kind,
    )
}
