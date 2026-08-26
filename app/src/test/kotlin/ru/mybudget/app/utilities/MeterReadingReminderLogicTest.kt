package ru.mybudget.app.utilities

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mybudget.app.data.UtilityMeterInfoEntity
import ru.mybudget.app.data.UtilityMeterReadingEntity
import java.time.LocalDate

class MeterReadingReminderLogicTest {
    @Test
    fun isOnOrAfterReminderDay_respectsConfiguredDay() {
        val today = LocalDate.of(2026, 8, 24)
        assertFalse(MeterReadingReminderLogic.isOnOrAfterReminderDay(today, 25))
        assertTrue(MeterReadingReminderLogic.isOnOrAfterReminderDay(today.plusDays(1), 25))
    }

    @Test
    fun metersMissingCurrentMonthReadings_trueWhenAnyMeterHasNoReading() {
        val today = LocalDate.of(2026, 8, 26)
        val meters = listOf(
            UtilityMeterInfoEntity(groupName = "Вода", meterName = "ХВС"),
            UtilityMeterInfoEntity(groupName = "Вода", meterName = "ГВС"),
        )
        val readings = mapOf(
            ("Вода" to "ХВС") to listOf(
                UtilityMeterReadingEntity(
                    groupName = "Вода",
                    meterName = "ХВС",
                    periodLabel = "2026-08-20",
                    readingValue = 100.0,
                ),
            ),
            ("Вода" to "ГВС") to emptyList(),
        )
        assertTrue(MeterReadingReminderLogic.metersMissingCurrentMonthReadings(meters, readings, today))
    }

    @Test
    fun metersMissingCurrentMonthReadings_falseWhenAllMetersHaveReading() {
        val today = LocalDate.of(2026, 8, 26)
        val meters = listOf(
            UtilityMeterInfoEntity(groupName = "Вода", meterName = "ХВС"),
        )
        val readings = mapOf(
            ("Вода" to "ХВС") to listOf(
                UtilityMeterReadingEntity(
                    groupName = "Вода",
                    meterName = "ХВС",
                    periodLabel = "25.08.2026",
                    readingValue = 100.0,
                ),
            ),
        )
        assertFalse(MeterReadingReminderLogic.metersMissingCurrentMonthReadings(meters, readings, today))
    }
}
