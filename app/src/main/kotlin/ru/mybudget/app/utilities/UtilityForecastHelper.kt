package ru.mybudget.app.utilities

object UtilityForecastHelper {
    fun percentVsRecentAverage(current: Double, previousTotals: List<Double>): Int? {
        val valid = previousTotals.filter { it > 0.0 }
        if (valid.isEmpty() || current <= 0.0) return null
        val avg = valid.average()
        if (avg <= 0.0) return null
        return ((current - avg) / avg * 100.0).toInt()
    }

    fun previousMonthTotals(
        year: Int,
        month: Int,
        totalsByPeriod: Map<Pair<Int, Int>, Double>,
        lookback: Int = 3,
    ): List<Double> {
        val result = mutableListOf<Double>()
        var y = year
        var m = month
        repeat(lookback) {
            val prev = UtilityAnomalyHelper.previousPeriod(y, m)
            totalsByPeriod[prev]?.let { result += it }
            y = prev.first
            m = prev.second
        }
        return result
    }
}
