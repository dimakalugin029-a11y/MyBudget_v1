package ru.mybudget.app

import android.content.Context
import java.util.Calendar
import kotlin.math.max

object CategoryRunwayHelper {
    fun daysElapsedInMonth(): Int = Calendar.getInstance().get(Calendar.DAY_OF_MONTH).coerceAtLeast(1)

    fun dailyBurnRate(spentThisMonth: Double, daysElapsed: Int = daysElapsedInMonth()): Double {
        if (spentThisMonth <= 0.0 || daysElapsed <= 0) return 0.0
        return spentThisMonth / daysElapsed
    }

    fun daysRunway(balance: Double, spentThisMonth: Double): Int? {
        val rate = dailyBurnRate(spentThisMonth)
        if (rate <= 0.0 || balance <= 0.0) return null
        return max(0, (balance / rate).toInt())
    }

    fun formatRunwaySuffix(context: Context, balance: Double, spentThisMonth: Double): String? {
        val days = daysRunway(balance, spentThisMonth) ?: return null
        return context.getString(R.string.budget_runway_days, days)
    }
}
