package ru.mybudget.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mybudget.app.data.TransactionEntity
import java.time.LocalDate

class MonthForecastHelperTest {
    @Test
    fun build_projectsEndBalanceFromCurrentPace() {
        val today = LocalDate.of(2026, 3, 10)
        val txs = listOf(
            TransactionEntity(categoryId = 1, amount = 3000.0, type = "expense", description = ""),
            TransactionEntity(categoryId = 1, amount = 50000.0, type = "income", description = ""),
        )
        val forecast = MonthForecastHelper.build(
            currentBalance = 20000.0,
            monthTransactions = txs,
            today = today,
        ) ?: error("forecast expected")

        assertEquals("31.03", forecast.endDateLabel)
        assertTrue(forecast.projectedEndBalance > 20000.0)
    }
}
