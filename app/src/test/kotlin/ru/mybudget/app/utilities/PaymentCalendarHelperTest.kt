package ru.mybudget.app.utilities

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
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
}
