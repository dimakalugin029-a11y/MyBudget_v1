package ru.mybudget.app

import ru.mybudget.app.data.BudgetRepository

object ObligationDefaultAmountsSync {
    data class Result(
        val updatedCount: Int,
        val totalsByCategory: Map<Int, Double>,
        val breakdownByCategory: Map<Int, List<PlannedObligationHelper.ObligationCategoryLine>>,
    )

    suspend fun apply(repository: BudgetRepository, budgetId: Int): Result {
        val obligations = repository.getPlannedObligationsByBudgetOnce(budgetId)
        val breakdown = PlannedObligationHelper.breakdownByCategory(obligations)
        val totals = PlannedObligationHelper.distributionByCategory(obligations)
        totals.forEach { (categoryId, amount) ->
            repository.updateDefaultIncomeAmount(categoryId, amount)
        }
        return Result(
            updatedCount = totals.size,
            totalsByCategory = totals,
            breakdownByCategory = breakdown,
        )
    }
}
