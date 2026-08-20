package ru.mybudget.app

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.TimeUnit

object GoalProgressHelper {
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    private val displayFormat = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault())

    fun progressPercent(currentBalance: Double, targetAmount: Double): Int {
        if (targetAmount <= 0.0) return 0
        return ((currentBalance / targetAmount) * 100.0).toInt().coerceIn(0, 100)
    }

    fun isComplete(currentBalance: Double, targetAmount: Double): Boolean {
        return targetAmount > 0.0 && currentBalance >= targetAmount
    }

    fun daysUntilDeadline(deadline: String?): Int? {
        if (deadline.isNullOrBlank()) return null
        return runCatching {
            val parsed = dateFormat.parse(deadline) ?: return null
            val today = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis
            val due = Calendar.getInstance().apply {
                time = parsed
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis
            TimeUnit.MILLISECONDS.toDays(due - today).toInt()
        }.getOrNull()
    }

    fun monthlyContributionNeeded(currentBalance: Double, targetAmount: Double, daysLeft: Int?): Double? {
        if (daysLeft == null || daysLeft <= 0) return null
        val remaining = targetAmount - currentBalance
        if (remaining <= 0.0) return null
        val monthsLeft = maxOf(1.0, kotlin.math.ceil(daysLeft / 30.0))
        return remaining / monthsLeft
    }

    fun formatDeadlineLabel(deadline: String?): String? {
        if (deadline.isNullOrBlank()) return null
        return runCatching {
            dateFormat.parse(deadline)?.let { displayFormat.format(it) } ?: deadline
        }.getOrDefault(deadline)
    }
}
