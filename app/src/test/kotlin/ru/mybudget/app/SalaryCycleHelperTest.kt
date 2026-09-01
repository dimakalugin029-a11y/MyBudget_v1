package ru.mybudget.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mybudget.app.data.PlannedIncomeSourceEntity
import ru.mybudget.app.utilities.PaymentCalendarHelper
import ru.mybudget.app.utilities.PaymentCalendarHelper.EntryKind
import java.time.LocalDate

class SalaryCycleHelperTest {
    @Test
    fun compute_subtractsUpcomingPaymentsUntilPayday() {
        val today = LocalDate.of(2026, 9, 1)
        val sources = listOf(
            PlannedIncomeSourceEntity(
                budgetId = 1,
                name = "Аванс",
                amount = 45_000.0,
                sourceType = PlannedIncomeHelper.TYPE_ADVANCE,
                dayOfMonth = 10,
            ),
        )
        val paydayEpoch = PlannedObligationHelper.dueLocalDate(
            java.time.YearMonth.from(today),
            10,
        ).toEpochDay()
        val entries = listOf(
            PaymentCalendarHelper.Entry(
                epochDay = paydayEpoch,
                dateLabel = "10.09.2026",
                title = "Кредит",
                subtitle = "",
                amount = 20_000.0,
                kind = EntryKind.OBLIGATION,
            ),
        )

        val info = SalaryCycleHelper.compute(100_000.0, sources, entries, today)

        assertNotNull(info)
        assertTrue(info!!.isPaydayBased)
        assertEquals(80_000.0, info.availableUntilPayday, 0.01)
        assertEquals(20_000.0, info.committedUntilPayday, 0.01)
    }

    @Test
    fun computeMonthFallback_usesDaysLeftInMonth() {
        val info = SalaryCycleHelper.computeMonthFallback(31_000.0)

        assertNotNull(info)
        assertEquals(false, info!!.isPaydayBased)
        assertTrue(info.dailyAmount > 0.0)
    }
}
