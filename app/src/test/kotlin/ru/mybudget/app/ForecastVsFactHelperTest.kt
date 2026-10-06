package ru.mybudget.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ru.mybudget.app.data.TransactionEntity

class ForecastVsFactHelperTest {

    private fun tx(
        categoryId: Int,
        amount: Double,
        type: String,
        dateMs: Long = 1_800_000_000_000L,
    ) = TransactionEntity(
        id = 0,
        categoryId = categoryId,
        amount = amount,
        type = type,
        description = "",
        date = dateMs,
        groupId = null,
        participantLabel = "",
    )

    @Test
    fun factTotalsSumsIncomeAndExpense() {
        val transactions = listOf(
            tx(1, 50000.0, "income"),
            tx(2, 20000.0, "expense"),
            tx(3, 5000.0, "expense"),
            tx(4, -100.0, "expense"),
            tx(5, 999.0, "transfer"),
        )
        val (income, expense) = ForecastVsFactHelper.factTotals(transactions)
        assertEquals(50000.0, income, 0.001)
        assertEquals(25000.0, expense, 0.001)
    }

    @Test
    fun deltaComputesAbsoluteAndPercent() {
        val delta = ForecastVsFactHelper.delta(100.0, 125.0)
        assertEquals(25.0, delta.absolute, 0.001)
        assertEquals(25.0, delta.percent!!, 0.001)
    }

    @Test
    fun deltaNegativeWhenFactLower() {
        val delta = ForecastVsFactHelper.delta(200.0, 150.0)
        assertEquals(-50.0, delta.absolute, 0.001)
        assertEquals(-25.0, delta.percent!!, 0.001)
    }

    @Test
    fun deltaPercentNullWhenForecastZero() {
        assertNull(ForecastVsFactHelper.delta(0.0, 100.0).percent)
    }

    @Test
    fun compareMonthComputesAllDeltas() {
        val transactions = listOf(
            tx(1, 90000.0, "income"),
            tx(2, 60000.0, "expense"),
        )
        val cmp = ForecastVsFactHelper.compareMonth(
            year = 2026,
            month = 10,
            forecastIncome = 100000.0,
            forecastExpense = 50000.0,
            transactions = transactions,
        )
        assertEquals(-10000.0, cmp.incomeDelta.absolute, 0.001)
        assertEquals(10000.0, cmp.expenseDelta.absolute, 0.001)
        assertEquals(50000.0, cmp.forecastNet, 0.001)
        assertEquals(30000.0, cmp.factNet, 0.001)
        assertEquals(-20000.0, cmp.netDelta.absolute, 0.001)
    }

    @Test
    fun compareLinesAddsUnplannedFacts() {
        val lines = ForecastVsFactHelper.compareLines(
            forecastLines = listOf("Продукты" to 10000.0),
            factByCategory = mapOf("Продукты" to 12000.0, "Кафе" to 3000.0),
        )
        assertEquals(2, lines.size)
        assertEquals("Продукты", lines[0].name)
        assertEquals(2000.0, lines[0].delta.absolute, 0.001)
        assertEquals("Кафе", lines[1].name)
        assertEquals(0.0, lines[1].forecastAmount, 0.001)
        assertEquals(3000.0, lines[1].factAmount, 0.001)
    }
}
