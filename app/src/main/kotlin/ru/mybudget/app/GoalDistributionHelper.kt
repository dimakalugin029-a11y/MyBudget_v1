package ru.mybudget.app

import ru.mybudget.app.data.SavingsGoalEntity
import kotlin.math.max

object GoalDistributionHelper {
    fun suggestedPerPaycheck(
        goal: SavingsGoalEntity,
        currentBalance: Double,
        paychecksPerMonth: Int = 2,
    ): Double? {
        if (goal.categoryId <= 0 || !goal.isActive) return null
        if (GoalProgressHelper.isComplete(currentBalance, goal.targetAmount)) return null
        val daysLeft = GoalProgressHelper.daysUntilDeadline(goal.deadline)
        val monthly = GoalProgressHelper.monthlyContributionNeeded(
            currentBalance,
            goal.targetAmount,
            daysLeft,
        ) ?: run {
            val remaining = goal.targetAmount - currentBalance
            if (remaining <= 0.0) return null
            MoneyFormat.roundMoney(remaining / 12.0)
        }
        return MoneyFormat.roundMoney(monthly / max(paychecksPerMonth, 1))
    }

    fun distributionByCategory(
        goals: List<SavingsGoalEntity>,
        balanceByCategoryId: Map<Int, Double>,
        paychecksPerMonth: Int = 2,
    ): Map<Int, Double> {
        val map = linkedMapOf<Int, Double>()
        for (goal in goals.filter { it.isActive && it.categoryId > 0 }) {
            val suggested = suggestedPerPaycheck(
                goal,
                balanceByCategoryId[goal.categoryId] ?: 0.0,
                paychecksPerMonth,
            ) ?: continue
            val current = map[goal.categoryId] ?: 0.0
            map[goal.categoryId] = MoneyFormat.roundMoney(current + suggested)
        }
        return map
    }

    fun skippedNoDeadlineCount(
        goals: List<SavingsGoalEntity>,
        balanceByCategoryId: Map<Int, Double>,
    ): Int {
        return goals.count { goal ->
            if (!goal.isActive || goal.categoryId <= 0) return@count false
            val balance = balanceByCategoryId[goal.categoryId] ?: 0.0
            if (GoalProgressHelper.isComplete(balance, goal.targetAmount)) return@count false
            if (GoalProgressHelper.daysUntilDeadline(goal.deadline) != null) return@count false
            goal.targetAmount > balance
        }
    }
}
