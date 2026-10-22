package ru.mybudget.app

import org.junit.Assert.*
import org.junit.Test
import ru.mybudget.app.data.PlannedObligationEntity
import ru.mybudget.app.utilities.PaymentCalendarHelper
import java.time.LocalDate
import java.time.ZoneId

class PaymentTrackingTest {
    private val old = PlannedObligationEntity(id = 7, budgetId = 1, name = "Платёж", amount = 100.0,
        periodType = "monthly", categoryId = 1, paychecksPerMonth = 1, dueMonth = 1, dueDay = 21,
        createdAt = LocalDate.of(2020, 1, 1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(),
        attentionStartMonth = "2026-10")

    @Test fun migrationBoundaryDoesNotInventYearsOfUnpaidPeriods() {
        val entries = PaymentCalendarHelper.buildEntries(emptyList(), emptyList(), emptyList(), listOf(old),
            categoryNames = emptyMap(), todayEpochDay = LocalDate.of(2026, 10, 21).toEpochDay(), horizonDays = 6)
        assertEquals(listOf("obligation:7:2026-10"), entries.map { it.eventKey })
    }
    @Test fun unpaidOctoberPeriodRemainsVisibleInNovember() {
        val entries = PaymentCalendarHelper.buildEntries(emptyList(), emptyList(), emptyList(), listOf(old),
            categoryNames = emptyMap(), todayEpochDay = LocalDate.of(2026, 11, 1).toEpochDay(), horizonDays = 30)
        assertEquals(listOf("obligation:7:2026-10", "obligation:7:2026-11"), entries.map { it.eventKey })
    }
    @Test fun earlierYearlyPeriodIsNotInventedBeforeTrackingBoundary() {
        val entries = PaymentCalendarHelper.buildEntries(emptyList(), emptyList(), emptyList(),
            listOf(old.copy(periodType = "yearly", dueMonth = 1)), categoryNames = emptyMap(),
            todayEpochDay = LocalDate.of(2026, 10, 21).toEpochDay(), horizonDays = 6)
        assertTrue(entries.isEmpty())
    }
    @Test fun backupBoundaryIsPreservedAndLegacyBackupStartsInImportMonth() {
        val today = LocalDate.of(2026, 11, 1)
        assertEquals("2026-10", PaymentTracking.importedStartMonth("2026-10", today))
        assertEquals("2026-11", PaymentTracking.importedStartMonth(null, today))
        assertEquals("2026-11", PaymentTracking.importedStartMonth("", today))
    }
}
