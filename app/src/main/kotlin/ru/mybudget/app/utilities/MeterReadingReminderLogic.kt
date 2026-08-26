package ru.mybudget.app.utilities

import ru.mybudget.app.data.UtilityMeterInfoEntity
import ru.mybudget.app.data.UtilityMeterReadingEntity
import java.time.LocalDate

object MeterReadingReminderLogic {
    fun monthKey(date: LocalDate): String {
        return "%04d-%02d".format(date.year, date.monthValue)
    }

    fun isOnOrAfterReminderDay(today: LocalDate, reminderDay: Int): Boolean {
        val day = reminderDay.coerceIn(1, 31)
        val effectiveDay = minOf(day, today.lengthOfMonth())
        return today.dayOfMonth >= effectiveDay
    }

    fun metersMissingCurrentMonthReadings(
        meters: List<UtilityMeterInfoEntity>,
        readingsByMeter: Map<Pair<String, String>, List<UtilityMeterReadingEntity>>,
        today: LocalDate,
    ): Boolean {
        if (meters.isEmpty()) return false
        val monthStart = today.withDayOfMonth(1).toEpochDay()
        val monthEnd = today.withDayOfMonth(today.lengthOfMonth()).toEpochDay()
        return meters.any { meter ->
            !hasReadingInRange(
                readingsByMeter[meter.groupName to meter.meterName].orEmpty(),
                monthStart,
                monthEnd,
            )
        }
    }

    fun hasReadingInRange(
        readings: List<UtilityMeterReadingEntity>,
        monthStart: Long,
        monthEnd: Long,
    ): Boolean {
        return readings.any { reading ->
            val epoch = UtilityExcelParser.parsePeriodToEpochDay(reading.periodLabel) ?: return@any false
            epoch in monthStart..monthEnd
        }
    }
}
