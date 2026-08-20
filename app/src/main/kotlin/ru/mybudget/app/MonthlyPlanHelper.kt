package ru.mybudget.app

import ru.mybudget.app.data.MonthlyCategoryPlanEntity
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

object MonthlyPlanHelper {
    data class MonthKey(val year: Int, val month: Int)

    fun currentMonth(): MonthKey {
        val cal = Calendar.getInstance()
        return MonthKey(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1)
    }

    fun monthRangeMs(year: Int, month: Int): Pair<Long, Long> {
        val start = Calendar.getInstance().apply {
            set(Calendar.YEAR, year)
            set(Calendar.MONTH, month - 1)
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val end = Calendar.getInstance().apply {
            timeInMillis = start.timeInMillis
            add(Calendar.MONTH, 1)
        }
        return start.timeInMillis to end.timeInMillis
    }

    fun formatMonthLabel(year: Int, month: Int): String {
        val cal = Calendar.getInstance().apply {
            set(Calendar.YEAR, year)
            set(Calendar.MONTH, month - 1)
            set(Calendar.DAY_OF_MONTH, 1)
        }
        return SimpleDateFormat("LLLL yyyy", Locale.getDefault()).format(cal.time)
    }

    fun isIncludedInPlan(category: BudgetCategory, monthlyPlan: MonthlyCategoryPlanEntity?): Boolean {
        return monthlyPlan?.isEnabled ?: (category.plannedAmount > 0.0)
    }

    fun suggestedAmount(category: BudgetCategory, monthlyPlan: MonthlyCategoryPlanEntity?): Double {
        val fromPlan = monthlyPlan?.plannedAmount ?: 0.0
        if (fromPlan > 0.0) return fromPlan
        if (category.defaultPlannedAmount > 0.0) return category.defaultPlannedAmount
        if (category.plannedAmount > 0.0) return category.plannedAmount
        return 0.0
    }

    fun shiftMonth(year: Int, month: Int, delta: Int): MonthKey {
        val cal = Calendar.getInstance().apply {
            set(Calendar.YEAR, year)
            set(Calendar.MONTH, month - 1)
            set(Calendar.DAY_OF_MONTH, 1)
            add(Calendar.MONTH, delta)
        }
        return MonthKey(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1)
    }

    fun isFutureMonth(year: Int, month: Int): Boolean {
        val now = currentMonth()
        return year > now.year || (year == now.year && month > now.month)
    }
}
