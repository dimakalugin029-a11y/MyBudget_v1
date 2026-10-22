package ru.mybudget.app

import org.junit.Assert.*
import org.junit.Test
import ru.mybudget.app.auto.AutoEntry
import ru.mybudget.app.auto.AutoEntryType
import ru.mybudget.app.auto.AutoPaymentMethod
import ru.mybudget.app.auto.AutoStatisticsHelper
import java.time.LocalDate
import java.time.YearMonth

class AutoStatisticsHelperTest {
    private fun entry(amount: Double, method: String, type: AutoEntryType = AutoEntryType.SERVICE,
        vehicle: Int = 1, date: String = "2026-10-10", liters: Double = 0.0) =
        AutoEntry(type, vehicleId = vehicle, dateEpochDay = LocalDate.parse(date).toEpochDay(),
            amount = amount, paymentMethod = method, liters = liters)

    @Test fun totalsSeparateBudgetCashAndUnpaidWithoutDoubleCounting() {
        val summary = AutoStatisticsHelper.summarize(listOf(
            entry(100.0, AutoPaymentMethod.BUDGET), entry(50.0, AutoPaymentMethod.CASH),
            entry(70.0, AutoPaymentMethod.UNPAID)), null, null, null)
        assertEquals(150.0, summary.totalPaid, 0.0)
        assertEquals(100.0, summary.fromBudget, 0.0)
        assertEquals(50.0, summary.cash, 0.0)
        assertEquals(70.0, summary.unpaid, 0.0)
        assertEquals(150.0, summary.byType.getValue(AutoEntryType.SERVICE), 0.0)
    }

    @Test fun dateBoundsAreInclusiveAndVehicleFilterIsApplied() {
        val summary = AutoStatisticsHelper.summarize(listOf(
            entry(10.0, AutoPaymentMethod.CASH, date = "2026-10-01"),
            entry(20.0, AutoPaymentMethod.CASH, date = "2026-10-31"),
            entry(30.0, AutoPaymentMethod.CASH, date = "2026-11-01"),
            entry(40.0, AutoPaymentMethod.CASH, vehicle = 2)), 1,
            LocalDate.parse("2026-10-01").toEpochDay(), LocalDate.parse("2026-10-31").toEpochDay())
        assertEquals(30.0, summary.totalPaid, 0.0)
        assertEquals(2, summary.entries.size)
    }

    @Test fun monthlyChartIncludesEmptyMonthsAndExcludesUnpaidEntries() {
        val summary = AutoStatisticsHelper.summarize(listOf(
            entry(10.0, AutoPaymentMethod.CASH, date = "2026-01-01"),
            entry(30.0, AutoPaymentMethod.BUDGET, date = "2026-03-31"),
            entry(50.0, AutoPaymentMethod.UNPAID, date = "2026-02-01")), null, null, null)
        assertEquals(listOf(10.0, 0.0, 30.0), summary.byMonth.values.toList())
        assertEquals(YearMonth.of(2026, 1), summary.byMonth.keys.first())
    }

    @Test fun averageFuelPriceIsWeightedByLitersAndUsesPaidFuelOnly() {
        val summary = AutoStatisticsHelper.summarize(listOf(
            entry(100.0, AutoPaymentMethod.CASH, AutoEntryType.FUEL, liters = 2.0),
            entry(600.0, AutoPaymentMethod.BUDGET, AutoEntryType.FUEL, liters = 10.0),
            entry(999.0, AutoPaymentMethod.UNPAID, AutoEntryType.FUEL, liters = 100.0)), null, null, null)
        assertEquals(12.0, summary.fuelLiters, 0.0)
        assertEquals(700.0 / 12.0, summary.averageFuelPrice!!, 1e-9)
    }

    @Test fun legacyPaymentIsInferredFromTransactionAndCashSurvivesWithoutOne() {
        assertEquals(AutoPaymentMethod.BUDGET, AutoPaymentMethod.resolve(null, 15))
        assertEquals(AutoPaymentMethod.UNPAID, AutoPaymentMethod.resolve(null, null))
        assertEquals(AutoPaymentMethod.CASH, AutoPaymentMethod.resolve(AutoPaymentMethod.CASH, null))
    }
}
