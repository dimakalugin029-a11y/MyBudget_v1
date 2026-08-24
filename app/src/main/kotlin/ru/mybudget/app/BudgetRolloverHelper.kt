package ru.mybudget.app

data class RolloverCandidate(
    val category: BudgetCategory,
    val label: String,
    val balance: Double,
)

object BudgetRolloverHelper {
    fun candidates(
        categories: List<BudgetCategory>,
        parentsById: Map<Int, String>,
        budgetId: Int,
        hasSubcategories: (Int) -> Boolean,
    ): List<RolloverCandidate> {
        return categories
            .filter { cat ->
                cat.budgetId == budgetId &&
                    cat.isActive &&
                    cat.parentId != 0 &&
                    !hasSubcategories(cat.id) &&
                    cat.currentBalance > 0.01
            }
            .sortedByDescending { it.currentBalance }
            .map { cat ->
                val parent = parentsById[cat.parentId]
                val prefix = if (parent.isNullOrEmpty()) "" else "$parent → "
                RolloverCandidate(cat, prefix + cat.name, cat.currentBalance)
            }
    }
}
