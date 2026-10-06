package ru.mybudget.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ForecastSnapshotCodecTest {

    private val month = ForecastHelper.ForecastMonth(
        year = 2026,
        month = 11,
        incomeLines = listOf(ForecastHelper.IncomeLine(3, "Зарплата", 100000.0)),
        expenseLines = listOf(
            ForecastHelper.ExpenseLine(7, "Аренда", 30000.0, ForecastHelper.ExpenseSource.PLAN),
            ForecastHelper.ExpenseLine(9, "Цель", 5000.0, ForecastHelper.ExpenseSource.GOAL),
        ),
        totalIncome = 100000.0,
        totalExpense = 35000.0,
        mandatoryExpense = 30000.0,
        variableExpense = 0.0,
        goalsExpense = 5000.0,
        net = 65000.0,
        projectedEndBalance = 165000.0,
    )

    @Test
    fun encodeDecodeRoundTrip() {
        val json = ForecastSnapshotCodec.encode(month)
        val dto = ForecastSnapshotCodec.decode(json)
        assertNotNull(dto)
        assertEquals(1, dto!!.incomes.size)
        assertEquals(3, dto.incomes[0].sourceId)
        assertEquals("Зарплата", dto.incomes[0].name)
        assertEquals(100000.0, dto.incomes[0].amount, 0.001)
        assertEquals(2, dto.expenses.size)
        assertEquals(7, dto.expenses[0].categoryId)
        assertEquals("PLAN", dto.expenses[0].source)
        assertEquals("GOAL", dto.expenses[1].source)
    }

    @Test
    fun decodeInvalidJsonReturnsNull() {
        assertNull(ForecastSnapshotCodec.decode("not a json"))
        assertNull(ForecastSnapshotCodec.decode(""))
    }
}
