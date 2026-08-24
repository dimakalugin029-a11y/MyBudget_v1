package ru.mybudget.app

import java.util.Calendar

object BudgetPlanHelper {
    enum class ListFilter {
        ALL,
        NON_ZERO,
        OVERSPEND,
    }

    fun monthStartMillis(): Long {
        return Calendar.getInstance().apply {
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    fun daysLeftInMonth(): Int {
        val cal = Calendar.getInstance()
        val today = cal.get(Calendar.DAY_OF_MONTH)
        val lastDay = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
        return maxOf(0, lastDay - today + 1)
    }

    fun safeToSpendDaily(totalBalance: Double): Double? {
        val daysLeft = daysLeftInMonth()
        if (daysLeft <= 0 || totalBalance <= 0.0) return null
        return totalBalance / daysLeft
    }

    fun planPercent(spent: Double, planned: Double): Int {
        if (planned <= 0.0) return 0
        return ((spent / planned) * 100.0).toInt().coerceIn(0, 100)
    }

    fun isOverspent(spent: Double, planned: Double, thresholdPercent: Int): Boolean {
        return planned > 0.0 && spent >= (thresholdPercent * planned) / 100.0
    }

    fun isCategoryOverspent(
        category: BudgetCategory,
        spent: Double,
        thresholdPercent: Int,
        plannedAmount: Double = category.plannedAmount,
    ): Boolean {
        return category.currentBalance < 0.0 || isOverspent(spent, plannedAmount, thresholdPercent)
    }

    fun matchesFilter(
        category: BudgetCategory,
        spent: Double,
        filter: ListFilter,
        thresholdPercent: Int,
    ): Boolean {
        return when (filter) {
            ListFilter.ALL -> true
            ListFilter.NON_ZERO -> category.currentBalance != 0.0 || spent != 0.0
            ListFilter.OVERSPEND -> isCategoryOverspent(category, spent, thresholdPercent)
        }
    }
}
