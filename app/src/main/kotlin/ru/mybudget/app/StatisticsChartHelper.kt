package ru.mybudget.app

import ru.mybudget.app.data.TransactionEntity
import java.util.concurrent.TimeUnit

object StatisticsChartHelper {
    private val dayMs = TimeUnit.DAYS.toMillis(1)

    data class DailyPoint(val dayKey: Long, val value: Double)

    data class BalanceSeries(
        val points: List<DailyPoint>,
        val startBalance: Double,
        val endBalance: Double,
    )

    data class FlowSeries(val points: List<DailyPoint>)

    fun signedAmount(type: String, amount: Double): Double =
        if (type == "income") amount else -amount

    fun buildBalanceSeries(
        transactions: List<TransactionEntity>,
        fromMs: Long,
        toMs: Long,
        currentBalance: Double,
        snapshotsByDay: Map<Long, Double> = emptyMap(),
        nowMs: Long = System.currentTimeMillis(),
    ): BalanceSeries {
        val fromDay = fromMs / dayMs
        val toDay = toMs / dayMs
        val todayDay = nowMs / dayMs
        if (fromDay > toDay) {
            return BalanceSeries(emptyList(), currentBalance, currentBalance)
        }

        val inRange = transactions.filter { it.date in fromMs..toMs }
        val byDay = inRange.groupBy { it.date / dayMs }
        val netInRange = inRange.sumOf { signedAmount(it.type, it.amount) }
        val endBalance = snapshotsByDay[todayDay] ?: currentBalance
        var running = endBalance - netInRange

        val points = mutableListOf<DailyPoint>()
        var day = fromDay
        while (day <= toDay) {
            val dayNet = byDay[day]?.sumOf { signedAmount(it.type, it.amount) } ?: 0.0
            running += dayNet
            val value = snapshotsByDay[day] ?: running
            if (snapshotsByDay.containsKey(day)) {
                running = value
            }
            points += DailyPoint(day, value)
            day++
        }

        val startBalance = points.firstOrNull()?.value ?: running
        val lastBalance = points.lastOrNull()?.value ?: endBalance
        return BalanceSeries(points, startBalance, lastBalance)
    }

    fun buildDailyFlowSeries(
        transactions: List<TransactionEntity>,
        fromMs: Long,
        toMs: Long,
    ): FlowSeries {
        val fromDay = fromMs / dayMs
        val toDay = toMs / dayMs
        if (fromDay > toDay) return FlowSeries(emptyList())

        val byDay = transactions
            .filter { it.date in fromMs..toMs }
            .groupBy { it.date / dayMs }

        val points = mutableListOf<DailyPoint>()
        var day = fromDay
        while (day <= toDay) {
            val net = byDay[day]?.sumOf { signedAmount(it.type, it.amount) } ?: 0.0
            points += DailyPoint(day, net)
            day++
        }
        return FlowSeries(points)
    }

    fun yAxisBounds(values: List<Double>): Pair<Float, Float> {
        if (values.isEmpty()) return 0f to 100f
        var min = values.min()
        var max = values.max()
        if (min == max) {
            val pad = kotlin.math.max(kotlin.math.abs(max) * 0.1, 1_000.0)
            min -= pad
            max += pad
        } else {
            val pad = (max - min) * 0.08
            min -= pad
            max += pad
        }
        return min.toFloat() to max.toFloat()
    }

    fun xLabelCount(dayCount: Int): Int = when {
        dayCount <= 1 -> 1
        dayCount <= 7 -> dayCount
        dayCount <= 31 -> 5
        dayCount <= 90 -> 4
        else -> 5
    }

    fun dayKeyToMillis(dayKey: Long): Long = dayKey * dayMs
}
