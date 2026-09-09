package ru.mybudget.app.utilities

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mybudget.app.data.UtilityBillEntity
import ru.mybudget.app.data.UtilityPropertyEntity
import java.time.LocalDate

class UtilityCalendarForecastHelperTest {
    @Test
    fun buildForecasts_addsForecastForMissingMonth() {
        val property = UtilityPropertyEntity(id = 1, name = "Квартира")
        val bills = listOf(
            UtilityBillEntity(id = 10, propertyId = 1, year = 2026, month = 6, apartmentArea = 50.0),
            UtilityBillEntity(id = 11, propertyId = 1, year = 2026, month = 7, apartmentArea = 50.0),
            UtilityBillEntity(id = 12, propertyId = 1, year = 2026, month = 8, apartmentArea = 50.0),
        )
        val totals = mapOf(10 to 3000.0, 11 to 4000.0, 12 to 5000.0)
        val today = LocalDate.of(2026, 9, 1)
        val horizonEnd = LocalDate.of(2026, 10, 31)

        val forecasts = UtilityCalendarForecastHelper.buildForecasts(
            properties = listOf(property),
            bills = bills,
            totalsByBillId = totals,
            today = today,
            horizonEnd = horizonEnd,
            paymentDays = mapOf(1 to 10),
        )

        assertTrue(forecasts.isNotEmpty())
        val september = forecasts.firstOrNull { it.year == 2026 && it.month == 9 }
        assertTrue(september != null)
        assertEquals(4000.0, september!!.forecastTotal, 0.01)
    }

    @Test
    fun buildForecasts_skipsWhenBillAlreadyExists() {
        val property = UtilityPropertyEntity(id = 1, name = "Дом")
        val bills = listOf(
            UtilityBillEntity(id = 1, propertyId = 1, year = 2026, month = 9, apartmentArea = 50.0),
        )
        val today = LocalDate.of(2026, 9, 1)

        val forecasts = UtilityCalendarForecastHelper.buildForecasts(
            properties = listOf(property),
            bills = bills,
            totalsByBillId = mapOf(1 to 2500.0),
            today = today,
            horizonEnd = today.plusDays(30),
            paymentDays = mapOf(1 to 10),
        )

        assertTrue(forecasts.none { it.year == 2026 && it.month == 9 })
    }
}
