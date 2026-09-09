package ru.mybudget.app

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

object WhatIfScenarioHelper {
    data class GoalSimulation(
        val goalName: String,
        val currentBalance: Double,
        val targetAmount: Double,
        val remaining: Double,
        val extraPerMonth: Double,
        val baseMonthly: Double,
        val monthsToComplete: Int?,
        val projectedDateLabel: String?,
    )

    data class ObligationShift(
        val obligationName: String,
        val amount: Double,
        val freedThisMonth: Double,
        val dueNextMonthLabel: String,
    )

    fun simulateGoalWithExtra(
        goalName: String,
        currentBalance: Double,
        targetAmount: Double,
        extraPerMonth: Double,
        daysUntilDeadline: Int?,
    ): GoalSimulation {
        val remaining = (targetAmount - currentBalance).coerceAtLeast(0.0)
        val baseMonthly = GoalProgressHelper.monthlyContributionNeeded(
            currentBalance,
            targetAmount,
            daysUntilDeadline,
        ) ?: 0.0
        val totalMonthly = (baseMonthly + extraPerMonth).coerceAtLeast(0.0)
        val months = if (remaining <= 0.0) {
            0
        } else if (totalMonthly <= 0.0) {
            null
        } else {
            kotlin.math.ceil(remaining / totalMonthly).toInt().coerceAtLeast(1)
        }
        val projectedDateLabel = months?.let { projectMonthsAhead(it) }
        return GoalSimulation(
            goalName = goalName,
            currentBalance = currentBalance,
            targetAmount = targetAmount,
            remaining = remaining,
            extraPerMonth = extraPerMonth,
            baseMonthly = baseMonthly,
            monthsToComplete = months,
            projectedDateLabel = projectedDateLabel,
        )
    }

    fun simulateOneTimeBonusToGoal(
        goalName: String,
        currentBalance: Double,
        targetAmount: Double,
        bonusAmount: Double,
    ): GoalSimulation {
        val newBalance = currentBalance + bonusAmount.coerceAtLeast(0.0)
        return simulateGoalWithExtra(
            goalName = goalName,
            currentBalance = newBalance,
            targetAmount = targetAmount,
            extraPerMonth = 0.0,
            daysUntilDeadline = GoalProgressHelper.daysUntilDeadline(null),
        ).copy(
            currentBalance = currentBalance,
            remaining = (targetAmount - newBalance).coerceAtLeast(0.0),
        )
    }

    fun simulateObligationShiftByMonth(
        obligationName: String,
        amount: Double,
        nextMonthLabel: String,
    ): ObligationShift {
        return ObligationShift(
            obligationName = obligationName,
            amount = amount,
            freedThisMonth = amount,
            dueNextMonthLabel = nextMonthLabel,
        )
    }

    private fun projectMonthsAhead(months: Int): String {
        val cal = Calendar.getInstance().apply {
            add(Calendar.MONTH, months)
        }
        return SimpleDateFormat("LLLL yyyy", Locale("ru")).format(cal.time)
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale("ru")) else it.toString() }
    }
}
