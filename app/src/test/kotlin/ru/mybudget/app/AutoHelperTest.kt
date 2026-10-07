package ru.mybudget.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mybudget.app.data.AutoRepository
import ru.mybudget.app.data.VehicleEntity
import ru.mybudget.app.data.VehicleFuelLogEntity
import ru.mybudget.app.data.VehicleInsuranceEntity

class AutoHelperTest {

    private fun insurance(endEpochDay: Long) = VehicleInsuranceEntity(
        id = 1,
        vehicleId = 1,
        startDateEpochDay = endEpochDay - 365,
        endDateEpochDay = endEpochDay,
        amount = 10000.0,
        categoryId = 5L,
    )

    @Test
    fun insuranceReminderDueWithin30And7Days() {
        val today = 20_000L
        assertTrue(AutoRepository.isReminderDue(today + 30, today))
        assertTrue(AutoRepository.isReminderDue(today + 7, today))
        assertTrue(AutoRepository.isReminderDue(today, today))
        assertFalse(AutoRepository.isReminderDue(today + 31, today))
        assertFalse(AutoRepository.isReminderDue(today - 1, today))
    }

    @Test
    fun daysUntilIsEndMinusToday() {
        assertEquals(7L, AutoRepository.daysUntil(1007L, 1000L))
        assertEquals(-3L, AutoRepository.daysUntil(997L, 1000L))
    }

    @Test
    fun filterDueOnlyHits30And7DayMarks() {
        val today = 50_000L
        val vehicles = listOf(VehicleEntity(id = 1, name = "Мой автомобиль"))
        val insurances = listOf(
            insurance(today + 30),
            insurance(today + 29),
            insurance(today + 7),
            insurance(today + 8),
            insurance(today + 100),
        )
        val due = InsuranceReminderNotifier.filterDue(insurances, vehicles, today)
        assertEquals(listOf(today + 30, today + 7), due.map { it.first.endDateEpochDay })
    }

    @Test
    fun fuelConsumptionPer100KmUsesLitersAfterFirstFill() {
        val logs = listOf(
            VehicleFuelLogEntity(vehicleId = 1, dateEpochDay = 1, liters = 40.0, pricePerLiter = 50.0, amount = 2000.0, mileageKm = 10_000, categoryId = 1L),
            VehicleFuelLogEntity(vehicleId = 1, dateEpochDay = 2, liters = 30.0, pricePerLiter = 50.0, amount = 1500.0, mileageKm = 10_400, categoryId = 1L),
            VehicleFuelLogEntity(vehicleId = 1, dateEpochDay = 3, liters = 20.0, pricePerLiter = 50.0, amount = 1000.0, mileageKm = 10_800, categoryId = 1L),
        )
        val result = AutoHelperTestBridge.consumption(logs)
        assertEquals(6.25, result!!, 0.001)
    }

    @Test
    fun fuelConsumptionNullWithoutMileageOrSingleFill() {
        assertNull(AutoHelperTestBridge.consumption(emptyList()))
        assertNull(
            AutoHelperTestBridge.consumption(
                listOf(
                    VehicleFuelLogEntity(vehicleId = 1, dateEpochDay = 1, liters = 40.0, pricePerLiter = 50.0, amount = 2000.0, mileageKm = 10_000, categoryId = 1L),
                ),
            ),
        )
        assertNull(
            AutoHelperTestBridge.consumption(
                listOf(
                    VehicleFuelLogEntity(vehicleId = 1, dateEpochDay = 1, liters = 40.0, pricePerLiter = 50.0, amount = 2000.0, mileageKm = null, categoryId = 1L),
                    VehicleFuelLogEntity(vehicleId = 1, dateEpochDay = 2, liters = 40.0, pricePerLiter = 50.0, amount = 2000.0, mileageKm = 10_400, categoryId = 1L),
                ),
            ),
        )
    }
}

object AutoHelperTestBridge {
    fun consumption(logs: List<VehicleFuelLogEntity>): Double? {
        if (logs.any { it.mileageKm == null }) return null
        val sorted = logs.sortedBy { it.mileageKm }
        if (sorted.size < 2) return null
        val distance = sorted.last().mileageKm!! - sorted.first().mileageKm!!
        if (distance <= 0) return null
        val liters = sorted.drop(1).sumOf { it.liters }
        if (liters <= 0.0) return null
        return liters * 100.0 / distance
    }
}
