package ru.mybudget.app

import java.time.LocalDate

object AttentionRules {
    fun goalNeedsAction(balance: Double, target: Double, deadline: String?, today: LocalDate): Boolean {
        if (target <= 0.0 || GoalProgressHelper.isComplete(balance, target)) return false
        val value = deadline ?: return false
        val due = runCatching { LocalDate.parse(value) }.getOrNull() ?: return false
        return due.toEpochDay() <= today.toEpochDay() + 6
    }
}
