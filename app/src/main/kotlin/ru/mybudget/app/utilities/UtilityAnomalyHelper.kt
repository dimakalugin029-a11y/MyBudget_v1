package ru.mybudget.app.utilities

object UtilityAnomalyHelper {
    const val THRESHOLD_PERCENT = 25

    fun percentChange(current: Double, previous: Double?): Int? {
        if (previous == null || previous <= 0.0 || current <= 0.0) return null
        val change = ((current - previous) / previous * 100.0).toInt()
        return if (kotlin.math.abs(change) >= THRESHOLD_PERCENT) change else null
    }

    fun previousPeriod(year: Int, month: Int): Pair<Int, Int> {
        return if (month == 1) (year - 1) to 12 else year to (month - 1)
    }
}
