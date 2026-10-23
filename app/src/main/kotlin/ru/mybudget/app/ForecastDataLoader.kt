package ru.mybudget.app

import androidx.room.withTransaction
import ru.mybudget.app.data.MonthlyCategoryPlanEntity
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/** All forecast entry points use the same budget-scoped, consistent database snapshot. */
object ForecastDataLoader {
    suspend fun load(manager: BudgetManager, budgetId: Int, horizon: ForecastHelper.Horizon,
        scenario: ForecastHelper.Scenario, today: LocalDate = LocalDate.now()): ForecastHelper.Inputs = manager.database.withTransaction {
        val dao = manager.database.budgetDao()
        val categories = dao.getAllCategoriesForExport().filter { it.budgetId == budgetId }
        val ids = categories.map { it.id }.toSet()
        val activeIds = categories.filter { it.isActive }.map { it.id }.toSet()
        val names = categories.associate { category -> category.id to
            listOfNotNull(categories.firstOrNull { it.id == category.parentId }?.name, category.name).joinToString(" → ") }
        val goals = dao.getAllSavingsGoalsList().filter { it.isActive && it.categoryId in activeIds }
        val start = YearMonth.from(today).minusMonths(3).atDay(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val history = dao.getTransactionsInRange(start, System.currentTimeMillis() + 1).filter { it.categoryId in ids }
        val overrides = dao.getMonthlyPlansForBudget(budgetId)
        val plans = overrides.toMutableList()
        val parents = categories.filter { it.isActive }.map { it.parentId }.toSet()
        for (offset in 0..horizon.months) {
            val month = YearMonth.from(today).plusMonths(offset.toLong())
            categories.filter { it.isActive && it.id !in parents && it.plannedAmount > 0.0 }.forEach { category ->
                if (overrides.none { it.categoryId == category.id && it.year == month.year && it.month == month.monthValue })
                    plans += MonthlyCategoryPlanEntity(month.year, month.monthValue, category.id, budgetId, category.plannedAmount)
            }
        }
        ForecastHelper.Inputs(startBalance = dao.getRootBalanceSumForBudget(budgetId), today = today,
            horizon = horizon, scenario = scenario, incomeSources = dao.getPlannedIncomeSourcesByBudgetOnce(budgetId),
            obligations = dao.getPlannedObligationsByBudgetOnce(budgetId), monthlyPlans = plans,
            pastTransactions = history, goals = goals, goalBalances = goals.associate { goal ->
                goal.categoryId to (categories.firstOrNull { it.id == goal.categoryId }?.currentBalance ?: 0.0) },
            categoryNames = names, monthlyIncomePlans = dao.getMonthlyIncomePlansForBudget(budgetId),
            includeCurrentMonth = true, planReceipts=dao.getIncomePlanTransactions(budgetId), paidPeriods = ObligationPaymentHelper.paidKeys(dao.getObligationPaymentsByBudget(budgetId)))
    }
}
