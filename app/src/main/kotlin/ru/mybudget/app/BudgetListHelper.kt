package ru.mybudget.app

/** Presentation of the two-level budget tree. A root without children is a usable leaf. */
object BudgetListHelper {
    data class Group(val parent: BudgetCategory, val children: List<BudgetCategory>, val hasChildren: Boolean)

    fun visibleGroups(
        categories: List<BudgetCategory>,
        filter: BudgetPlanHelper.ListFilter,
        spent: Map<Int, Double>,
        planned: Map<Int, Double>,
        thresholdPercent: Int,
    ): List<Group> {
        val active = categories.filter { it.isActive }
        val byParent = active.groupBy { it.parentId }
        fun matches(category: BudgetCategory): Boolean = when (filter) {
            BudgetPlanHelper.ListFilter.ALL -> true
            BudgetPlanHelper.ListFilter.NON_ZERO -> BudgetPlanHelper.matchesFilter(
                category, spent[category.id] ?: 0.0, filter, thresholdPercent,
            )
            BudgetPlanHelper.ListFilter.OVERSPEND -> BudgetPlanHelper.isCategoryOverspent(
                category, spent[category.id] ?: 0.0, thresholdPercent,
                planned[category.id] ?: category.plannedAmount,
            )
        }
        return byParent[0].orEmpty().sortedWith(compareBy({ it.position }, { it.id })).mapNotNull { parent ->
            val children = byParent[parent.id].orEmpty().filter { it.budgetId == parent.budgetId }
                .sortedWith(compareBy({ it.position }, { it.id }))
            if (children.isEmpty()) {
                if (matches(parent)) Group(parent, emptyList(), false) else null
            } else {
                val visible = children.filter(::matches)
                if (filter == BudgetPlanHelper.ListFilter.ALL || visible.isNotEmpty()) Group(parent, visible, true) else null
            }
        }
    }
}
