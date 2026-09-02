package ru.mybudget.app

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.mybudget.app.data.TransactionEntity
import java.util.concurrent.TimeUnit

class StatisticsPeriodComparisonHelperTest {
    private val dayMs = TimeUnit.DAYS.toMillis(1)

    @Test
    fun previousRange_hasSameDuration() {
        val from = 10L * dayMs
        val to = from + 30L * dayMs
        val (prevFrom, prevTo) = StatisticsPeriodComparisonHelper.previousRange(from, to)
        assertEquals(to - from, prevTo - prevFrom)
        assertEquals(from - 1, prevTo)
    }

    @Test
    fun buildComparison_calculatesDeltas() {
        val current = listOf(
            tx("income", 10_000.0),
            tx("expense", 3_000.0),
        )
        val previous = listOf(
            tx("income", 8_000.0),
            tx("expense", 4_000.0),
        )
        val comparison = StatisticsPeriodComparisonHelper.buildComparison(
            currentTransactions = current,
            previousTransactions = previous,
            previousPeriodLabel = "prev",
        )
        assertEquals(2_000.0, comparison.incomeDelta, 0.01)
        assertEquals(-1_000.0, comparison.expenseDelta, 0.01)
        assertEquals(3_000.0, comparison.saldoDelta, 0.01)
    }

    private fun tx(type: String, amount: Double): TransactionEntity {
        return TransactionEntity(
            categoryId = 1,
            amount = amount,
            type = type,
            description = "",
            date = System.currentTimeMillis(),
        )
    }
}
