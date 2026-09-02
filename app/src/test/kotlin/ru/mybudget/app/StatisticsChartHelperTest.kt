package ru.mybudget.app

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.mybudget.app.data.TransactionEntity
import java.util.concurrent.TimeUnit

class StatisticsChartHelperTest {
    private val dayMs = TimeUnit.DAYS.toMillis(1)

    @Test
    fun buildBalanceSeries_endPointMatchesCurrentBalance() {
        val from = 10L * dayMs
        val to = 12L * dayMs + dayMs - 1
        val txs = listOf(
            tx(day = 11, type = "income", amount = 3_000.0),
            tx(day = 12, type = "expense", amount = 1_000.0),
        )

        val series = StatisticsChartHelper.buildBalanceSeries(
            transactions = txs,
            fromMs = from,
            toMs = to,
            currentBalance = 50_000.0,
            nowMs = to,
        )

        assertEquals(50_000.0, series.endBalance, 0.01)
        assertEquals(48_000.0, series.startBalance, 0.01)
        assertEquals(3, series.points.size)
    }

    @Test
    fun buildBalanceSeries_flatWhenNoTransactions() {
        val from = 5L * dayMs
        val to = 7L * dayMs

        val series = StatisticsChartHelper.buildBalanceSeries(
            transactions = emptyList(),
            fromMs = from,
            toMs = to,
            currentBalance = 50_000.0,
            nowMs = to,
        )

        assertEquals(50_000.0, series.startBalance, 0.01)
        assertEquals(50_000.0, series.endBalance, 0.01)
        assertEquals(3, series.points.size)
        assertEquals(50_000.0, series.points.last().value, 0.01)
    }

    @Test
    fun buildBalanceSeries_usesSnapshotForDay() {
        val from = 10L * dayMs
        val to = 12L * dayMs
        val txs = listOf(
            tx(day = 11, type = "income", amount = 5_000.0),
        )
        val snapshots = mapOf(11L to 55_000.0)

        val series = StatisticsChartHelper.buildBalanceSeries(
            transactions = txs,
            fromMs = from,
            toMs = to,
            currentBalance = 50_000.0,
            snapshotsByDay = snapshots,
            nowMs = to,
        )

        assertEquals(55_000.0, series.points[1].value, 0.01)
    }

    @Test
    fun buildDailyFlowSeries_returnsNetPerDay() {
        val from = 10L * dayMs
        val to = 12L * dayMs
        val txs = listOf(
            tx(day = 10, type = "income", amount = 2_000.0),
            tx(day = 11, type = "expense", amount = 500.0),
            tx(day = 11, type = "income", amount = 100.0),
        )

        val series = StatisticsChartHelper.buildDailyFlowSeries(txs, from, to)

        assertEquals(3, series.points.size)
        assertEquals(2_000.0, series.points[0].value, 0.01)
        assertEquals(-400.0, series.points[1].value, 0.01)
        assertEquals(0.0, series.points[2].value, 0.01)
    }

    private fun tx(day: Long, type: String, amount: Double): TransactionEntity {
        return TransactionEntity(
            categoryId = 1,
            amount = amount,
            type = type,
            description = "",
            date = day * dayMs + 12 * 3_600_000L,
        )
    }
}
