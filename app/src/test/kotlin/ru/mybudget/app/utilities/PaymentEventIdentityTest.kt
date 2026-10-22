package ru.mybudget.app.utilities

import org.junit.Assert.*
import org.junit.Test
import ru.mybudget.app.ObligationPaymentHelper
import ru.mybudget.app.data.PaymentReminderEntity
import ru.mybudget.app.data.PlannedObligationEntity
import ru.mybudget.app.data.RecurringTransactionEntity
import ru.mybudget.app.data.UtilityBillEntity
import java.time.LocalDate
import java.time.ZoneId

class PaymentEventIdentityTest {
    private val today = LocalDate.of(2026, 10, 21)
    private val obligation = PlannedObligationEntity(id = 7, budgetId = 1, name = "Связь",
        amount = 100.0, periodType = "monthly", categoryId = 2, paychecksPerMonth = 1, dueMonth = 1,
        dueDay = 21, createdAt = today.withDayOfMonth(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli())
    private val reminder = PaymentReminderEntity(id = 11, title = "Связь", amount = 120.0,
        categoryId = 2, dueDate = "2026-10-22", repeatType = "monthly", obligationId = 7)
    private val recurring = RecurringTransactionEntity(id = 12, categoryId = 2, amount = 100.0,
        type = "expense", repeatType = "monthly", nextDueDate = "2026-10-21", obligationId = 7)

    @Test fun linkedSourcesProduceOneCanonicalEventDespiteAmountAndDateDifferences() {
        val entries = PaymentCalendarHelper.buildEntries(listOf(reminder), listOf(recurring), emptyList(),
            listOf(obligation), categoryNames = mapOf(2 to "Связь"), todayEpochDay = today.toEpochDay(), horizonDays = 6)
        assertEquals(1, entries.size)
        assertEquals(PaymentCalendarHelper.EntryKind.OBLIGATION, entries.single().kind)
        assertEquals("obligation:7:2026-10", entries.single().eventKey)
        assertEquals(100.0, PaymentCalendarHelper.weekPaymentTotal(entries), 0.0)
    }

    @Test fun paidPeriodHidesItsStaleLinkedReminderAndAutoOperation() {
        val entries = PaymentCalendarHelper.buildEntries(listOf(reminder), listOf(recurring), emptyList(),
            listOf(obligation), categoryNames = emptyMap(), todayEpochDay = today.toEpochDay(), horizonDays = 6,
            paidObligationPeriods = setOf(ObligationPaymentHelper.PeriodKey(7, 2026, 10)))
        assertTrue(entries.isEmpty())
    }

    @Test fun recurringIncomeAndForecastDoNotIncreasePayableBudgetAmount() {
        val income = recurring.copy(obligationId = null, type = "income", amount = 50000.0)
        val entries = PaymentCalendarHelper.buildEntries(emptyList(), listOf(income), emptyList(), emptyList(),
            categoryNames = emptyMap(), todayEpochDay = today.toEpochDay(), horizonDays = 6,
            utilityForecasts = listOf(UtilityCalendarForecastHelper.ForecastEntry(1, "Квартира", 2026, 10,
                today.toEpochDay(), 5000.0)))
        assertEquals(0.0, PaymentCalendarHelper.weekPaymentTotal(entries), 0.0)
        assertEquals(5000.0, PaymentCalendarHelper.forecastTotal(entries), 0.0)
        assertTrue(entries.any { it.isIncome })
    }

    @Test fun overdueStandaloneReminderDoesNotDisappear() {
        val old = reminder.copy(obligationId = null, dueDate = "2026-09-15")
        val entries = PaymentCalendarHelper.buildEntries(listOf(old), emptyList(), emptyList(), emptyList(),
            categoryNames = emptyMap(), todayEpochDay = today.toEpochDay(), horizonDays = 6)
        assertEquals(LocalDate.of(2026, 9, 15).toEpochDay(), entries.single().epochDay)
    }

    @Test fun eachUtilityBillOccursOnceAcrossMultipleMonthsAndIsGlobal() {
        val bill = UtilityBillEntity(id = 9, propertyId = 1, year = 2026, month = 9, apartmentArea = 40.0)
        val entries = PaymentCalendarHelper.buildEntries(emptyList(), emptyList(),
            listOf(PaymentCalendarHelper.UnpaidUtilityBill(bill, 3000.0, "Квартира")), emptyList(),
            categoryNames = emptyMap(), todayEpochDay = today.toEpochDay(), horizonDays = 60)
        assertEquals(1, entries.size)
        assertTrue(entries.single().isGlobal)
        assertEquals("bill:9", entries.single().eventKey)
        assertEquals(0.0, PaymentCalendarHelper.weekPaymentTotal(entries), 0.0)
        assertEquals(3000.0, PaymentCalendarHelper.weekPaymentTotal(entries, includeGlobal = true), 0.0)
    }
}
