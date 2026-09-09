package ru.mybudget.app.utilities

import android.content.Context
import ru.mybudget.app.data.UtilityDao
import ru.mybudget.app.setup.UtilityPaymentReminderPreferences
import java.time.LocalDate

object UtilityCalendarForecastHelper {
    data class ForecastEntry(
        val propertyId: Int,
        val propertyName: String,
        val year: Int,
        val month: Int,
        val epochDay: Long,
        val forecastTotal: Double,
    )

    suspend fun loadForecasts(
        context: Context,
        utilityDao: UtilityDao,
        today: LocalDate = LocalDate.now(),
        horizonDays: Int = 60,
    ): List<ForecastEntry> {
        val properties = utilityDao.getAllProperties()
        val bills = utilityDao.getAllBills()
        val totalsByBillId = utilityDao.getBillGrandTotals().associate { it.billId to it.total }
        val paymentDays = properties.associate { property ->
            property.id to UtilityPaymentReminderPreferences.paymentDay(context, property.id)
        }
        return buildForecasts(
            properties = properties,
            bills = bills,
            totalsByBillId = totalsByBillId,
            today = today,
            horizonEnd = today.plusDays(horizonDays.toLong()),
            paymentDays = paymentDays,
        )
    }

    fun buildForecasts(
        properties: List<ru.mybudget.app.data.UtilityPropertyEntity>,
        bills: List<ru.mybudget.app.data.UtilityBillEntity>,
        totalsByBillId: Map<Int, Double>,
        today: LocalDate,
        horizonEnd: LocalDate,
        paymentDays: Map<Int, Int>,
        lookback: Int = 3,
    ): List<ForecastEntry> {
        if (properties.isEmpty()) return emptyList()

        val existingBillKeys = bills.map { Triple(it.propertyId, it.year, it.month) }.toSet()
        val result = mutableListOf<ForecastEntry>()
        val endYm = java.time.YearMonth.from(horizonEnd)
        val todayEpoch = today.toEpochDay()
        val maxEpoch = horizonEnd.toEpochDay()

        for (property in properties) {
            val paymentDay = paymentDays[property.id] ?: UtilityPaymentReminderPreferences.DEFAULT_DAY
            val propertyName = property.name.ifBlank { "Квартира" }
            val totalsByPeriod = bills
                .filter { it.propertyId == property.id }
                .associate { (it.year to it.month) to (totalsByBillId[it.id] ?: 0.0) }

            var ym = java.time.YearMonth.from(today)
            while (!ym.isAfter(endYm)) {
                val key = Triple(property.id, ym.year, ym.monthValue)
                if (!existingBillKeys.contains(key)) {
                    val previousTotals = UtilityForecastHelper.previousMonthTotals(
                        year = ym.year,
                        month = ym.monthValue,
                        totalsByPeriod = totalsByPeriod,
                        lookback = lookback,
                    )
                    val forecast = previousTotals.filter { it > 0.0 }.takeIf { it.isNotEmpty() }?.average()
                    if (forecast != null && forecast > 0.0) {
                        val dueDate = ru.mybudget.app.PlannedObligationHelper.dueLocalDate(ym, paymentDay)
                        val epoch = dueDate.toEpochDay()
                        if (epoch in todayEpoch..maxEpoch) {
                            result += ForecastEntry(
                                propertyId = property.id,
                                propertyName = propertyName,
                                year = ym.year,
                                month = ym.monthValue,
                                epochDay = epoch,
                                forecastTotal = forecast,
                            )
                        }
                    }
                }
                ym = ym.plusMonths(1)
            }
        }
        return result
    }
}
