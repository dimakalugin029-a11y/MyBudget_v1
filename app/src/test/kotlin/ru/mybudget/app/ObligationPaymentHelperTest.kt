package ru.mybudget.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mybudget.app.data.PlannedObligationEntity
import java.time.LocalDate

class ObligationPaymentHelperTest {
    @Test
    fun canPayNow_monthlyWhenUnpaidAndCategorySet() {
        val today = LocalDate.of(2026, 9, 2)
        val obligation = PlannedObligationEntity(
            budgetId = 1,
            name = "Интернет",
            amount = 500.0,
            periodType = PlannedObligationHelper.PERIOD_MONTHLY,
            categoryId = 3,
            paychecksPerMonth = 1,
            dueMonth = 1,
            dueDay = 2,
        ).copy(id = 10)

        assertTrue(ObligationPaymentHelper.canPayNow(obligation, emptySet(), today))
    }

    @Test
    fun canPayNow_falseWhenAlreadyPaidThisMonth() {
        val today = LocalDate.of(2026, 9, 2)
        val obligation = PlannedObligationEntity(
            budgetId = 1,
            name = "Интернет",
            amount = 500.0,
            periodType = PlannedObligationHelper.PERIOD_MONTHLY,
            categoryId = 3,
            paychecksPerMonth = 1,
            dueMonth = 1,
            dueDay = 2,
        ).copy(id = 10)
        val paid = setOf(ObligationPaymentHelper.PeriodKey(10, 2026, 9))

        assertFalse(ObligationPaymentHelper.canPayNow(obligation, paid, today))
        assertTrue(ObligationPaymentHelper.isPaidForActivePeriod(obligation, paid, today))
    }
}
