package ru.mybudget.app

import ru.mybudget.app.data.MonthlyCategoryPlanEntity
import ru.mybudget.app.data.MonthlyIncomePlanEntity
import ru.mybudget.app.data.PlannedIncomeSourceEntity
import ru.mybudget.app.data.TransactionEntity
import java.time.YearMonth

object PlanOverviewHelper {
    data class Summary(
        val incomePlanned: Double,
        val incomeReceived: Double,
        val incomeRemaining: Double,
        val expensePlanned: Double,
        val expenseSpent: Double,
        val hasIncomePlan: Boolean,
        val hasExpensePlan: Boolean,
    )

    fun summarize(
        budgetId: Int, month: YearMonth, categories: List<BudgetCategory>,
        sources: List<PlannedIncomeSourceEntity>, incomePlans: List<MonthlyIncomePlanEntity>,
        expensePlans: List<MonthlyCategoryPlanEntity>, transactions: List<TransactionEntity>,
    ): Summary {
        val scoped = categories.filter { it.budgetId == budgetId }
        val ids = scoped.map { it.id }.toSet()
        val active = scoped.filter { it.isActive }
        val parentIds = active.map { it.parentId }.toSet()
        val leaves = active.filter { it.id !in parentIds }
        val plans = expensePlans.filter { it.budgetId == budgetId && it.year == month.year && it.month == month.monthValue }
            .associateBy { it.categoryId }
        val (from, to) = MonthlyPlanHelper.monthRangeMs(month.year, month.monthValue)
        val inBudget = transactions.filter { it.categoryId in ids }
        val progress = sources.filter { it.isActive && it.budgetId == budgetId }
            .map { IncomePlanAmounts.progress(it, month, incomePlans, inBudget) }
        return Summary(
            MoneyFormat.roundMoney(progress.sumOf { it.planned }),
            MoneyFormat.roundMoney(progress.sumOf { it.received }),
            MoneyFormat.roundMoney(progress.sumOf { it.remaining }),
            MoneyFormat.roundMoney(leaves.sumOf { MonthlyPlanHelper.effectivePlannedAmount(it, plans[it.id]) }),
            MoneyFormat.roundMoney(inBudget.filter { it.type == "expense" && it.date >= from && it.date < to }.sumOf { it.amount }),
            progress.isNotEmpty(),
            leaves.any { it.plannedAmount > 0.0 || plans.containsKey(it.id) },
        )
    }
}
