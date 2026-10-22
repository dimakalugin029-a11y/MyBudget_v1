package ru.mybudget.app

import ru.mybudget.app.data.BalanceMovementEntity
import java.time.Instant
import java.time.ZoneId

object LedgerBalanceHelper {
    data class Series(val points: List<StatisticsChartHelper.DailyPoint>, val startBalance: Double?, val endBalance: Double?)
    fun build(rows: List<BalanceMovementEntity>, startedAt: Long?, from: Long, to: Long,
        now: Long = System.currentTimeMillis()): Series {
        if (startedAt == null || to <= startedAt || from >= to || from > now) return Series(emptyList(), null, null)
        val until = minOf(to, now + 1)
        if (until <= startedAt) return Series(emptyList(), null, null)
        val known = rows.filter { it.createdAt < until }
        val zone = ZoneId.systemDefault()
        var day = Instant.ofEpochMilli(maxOf(from, startedAt)).atZone(zone).toLocalDate()
        val last = Instant.ofEpochMilli(until - 1).atZone(zone).toLocalDate()
        val sorted = known.sortedWith(compareBy<BalanceMovementEntity> { it.createdAt }.thenBy { it.id })
        var index = 0
        var running = 0.0
        val points = mutableListOf<StatisticsChartHelper.DailyPoint>()
        while (!day.isAfter(last)) {
            val boundary = minOf(day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(), until)
            while (index < sorted.size && sorted[index].createdAt < boundary) {
                running += sorted[index].budgetDelta
                index++
            }
            points.add(StatisticsChartHelper.DailyPoint(day.toEpochDay(), MoneyFormat.roundMoney(running)))
            day = day.plusDays(1)
        }
        val opening = if (from >= startedAt) MoneyFormat.roundMoney(known.filter {
            it.createdAt < from || (it.createdAt == from && it.kind == "opening")
        }.sumOf { it.budgetDelta }) else null
        return Series(points, opening, points.lastOrNull()?.value)
    }
}
