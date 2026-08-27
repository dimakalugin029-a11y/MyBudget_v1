package ru.mybudget.app

import android.content.Context
import ru.mybudget.app.data.BudgetDatabase
import ru.mybudget.app.setup.MeterReadingReminderPreferences
import ru.mybudget.app.utilities.MeterReadingReminderLogic
import java.time.LocalDate

object MeterReadingReminderHelper {
    suspend fun shouldNotify(context: Context, today: LocalDate = LocalDate.now()): Boolean {
        if (!MeterReadingReminderPreferences.isEnabled(context)) return false
        val reminderDay = MeterReadingReminderPreferences.reminderDay(context)
        if (!MeterReadingReminderLogic.isOnOrAfterReminderDay(today, reminderDay)) return false
        val monthKey = MeterReadingReminderLogic.monthKey(today)
        if (MeterReadingReminderPreferences.getLastNotifiedMonth(context) == monthKey) return false

        val utilityDao = BudgetDatabase.getInstance(context).utilityDao()
        val properties = utilityDao.getAllProperties()
        if (properties.isEmpty()) return false

        for (property in properties) {
            val meters = utilityDao.getAllMeterInfo(property.id)
            if (meters.isEmpty()) continue
            val readingsByMeter = utilityDao.getAllMeterReadings(property.id)
                .groupBy { it.groupName to it.meterName }
            if (MeterReadingReminderLogic.metersMissingCurrentMonthReadings(meters, readingsByMeter, today)) {
                return true
            }
        }
        return false
    }

    suspend fun processReminder(context: Context, today: LocalDate = LocalDate.now()) {
        if (!shouldNotify(context, today)) return
        MeterReadingNotifier.show(context)
        MeterReadingReminderPreferences.setLastNotifiedMonth(
            context,
            MeterReadingReminderLogic.monthKey(today),
        )
    }
}
