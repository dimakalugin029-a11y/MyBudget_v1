package ru.mybudget.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import ru.mybudget.app.data.PlannedObligationEntity

class ObligationReminderHelperTest {
    @Test
    fun buildReminderEntity_linksObligationAndUsesMonthlyRepeat() {
        val obligation = PlannedObligationEntity(
            id = 7,
            budgetId = 1,
            name = "Кредит",
            amount = 10_000.0,
            periodType = PlannedObligationHelper.PERIOD_MONTHLY,
            categoryId = 5,
            paychecksPerMonth = 2,
            dueMonth = 1,
            dueDay = 15,
            remindEnabled = true,
        )

        val reminder = ObligationReminderHelper.buildReminderEntity(obligation)

        assertNotNull(reminder)
        assertEquals(7, reminder!!.obligationId)
        assertEquals("Кредит", reminder.title)
        assertEquals(PlannedObligationHelper.PERIOD_MONTHLY, reminder.repeatType)
    }

    @Test
    fun buildRecurringEntity_onlyForMonthlyObligations() {
        val monthly = PlannedObligationEntity(
            id = 3,
            budgetId = 1,
            name = "Интернет",
            amount = 600.0,
            periodType = PlannedObligationHelper.PERIOD_MONTHLY,
            categoryId = 2,
            paychecksPerMonth = 1,
            dueMonth = 1,
            dueDay = 10,
            autoPostEnabled = true,
        )
        val yearly = monthly.copy(periodType = PlannedObligationHelper.PERIOD_YEARLY, dueMonth = 6)

        assertNotNull(ObligationReminderHelper.buildRecurringEntity(monthly))
        assertNull(ObligationReminderHelper.buildRecurringEntity(yearly))
    }
}
