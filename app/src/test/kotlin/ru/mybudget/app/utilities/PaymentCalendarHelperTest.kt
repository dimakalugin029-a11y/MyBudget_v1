package ru.mybudget.app.utilities

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mybudget.app.PlannedIncomeHelper
import java.time.LocalDate
import ru.mybudget.app.utilities.PaymentCalendarHelper.EntryKind

class PaymentCalendarHelperTest {
    @Test
    fun dedupeOverlappingEntries_hidesReminderWhenObligationMatches() {
        val obligation = PaymentCalendarHelper.Entry(
            epochDay = 100L,
            dateLabel = "01.09.2026",
            title = "Кредит / месяц",
            subtitle = "",
            amount = 10_000.0,
            kind = EntryKind.OBLIGATION,
            categoryId = 5,
        )
        val reminder = obligation.copy(
            title = "Кредит",
            kind = EntryKind.REMINDER,
            sourceRef = PaymentCalendarHelper.SourceRef(reminderId = 1),
        )
        val utility = obligation.copy(
            title = "Коммуналка",
            kind = EntryKind.UTILITY,
            categoryId = 0,
            sourceRef = PaymentCalendarHelper.SourceRef(propertyId = 1),
        )

        val result = PaymentCalendarHelper.dedupeOverlappingEntries(listOf(obligation, reminder, utility))

        assertEquals(2, result.size)
        assertTrue(result.any { it.kind == EntryKind.OBLIGATION })
        assertTrue(result.any { it.kind == EntryKind.UTILITY })
        assertTrue(result.none { it.kind == EntryKind.REMINDER })
    }

    @Test
    fun dedupeOverlappingEntries_keepsReminderWhenAmountDiffers() {
        val obligation = PaymentCalendarHelper.Entry(
            epochDay = 100L,
            dateLabel = "01.09.2026",
            title = "Кредит / месяц",
            subtitle = "",
            amount = 10_000.0,
            kind = EntryKind.OBLIGATION,
            categoryId = 5,
        )
        val reminder = obligation.copy(
            title = "Доп. платёж",
            amount = 1_000.0,
            kind = EntryKind.REMINDER,
            sourceRef = PaymentCalendarHelper.SourceRef(reminderId = 2),
        )

        val result = PaymentCalendarHelper.dedupeOverlappingEntries(listOf(obligation, reminder))

        assertEquals(2, result.size)
    }

    @Test
    fun buildEntries_includesPlannedIncomeOnExpectedDays() {
        val today = LocalDate.of(2026, 8, 31)
        val todayEpoch = today.toEpochDay()
        val income = listOf(
            ru.mybudget.app.data.PlannedIncomeSourceEntity(
                id = 7,
                budgetId = 1,
                name = "Аванс",
                amount = 45_000.0,
                sourceType = PlannedIncomeHelper.TYPE_ADVANCE,
                dayOfMonth = 25,
            ),
        )

        val result = PaymentCalendarHelper.buildEntries(
            reminders = emptyList(),
            recurring = emptyList(),
            unpaidUtilityBills = emptyList(),
            obligations = emptyList(),
            plannedIncome = income,
            categoryNames = emptyMap(),
            todayEpochDay = todayEpoch,
            horizonDays = 60,
        )

        assertTrue(result.any { it.kind == EntryKind.INCOME && it.title == "Аванс" })
        assertEquals(45_000.0, result.first { it.kind == EntryKind.INCOME }.amount ?: 0.0, 0.01)
    }
}
