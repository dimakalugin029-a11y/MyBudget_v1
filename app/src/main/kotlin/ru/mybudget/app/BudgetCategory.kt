package ru.mybudget.app

import ru.mybudget.app.data.BudgetCategoryEntity

data class BudgetCategory(
    val id: Int,
    var name: String,
    val parentId: Int,
    val budgetId: Int = 1,
    var plannedAmount: Double = 0.0,
    var currentBalance: Double = 0.0,
    var defaultIncomeAmount: Double = 0.0,
    var defaultPlannedAmount: Double = 0.0,
    var isActive: Boolean = true,
    var position: Int = 0,
    var colorHex: String = "",
) {
    fun toEntity(): BudgetCategoryEntity = BudgetCategoryEntity(
        id = id,
        name = name,
        parentId = parentId,
        budgetId = budgetId,
        plannedAmount = plannedAmount,
        currentBalance = currentBalance,
        defaultIncomeAmount = defaultIncomeAmount,
        defaultPlannedAmount = defaultPlannedAmount,
        isActive = isActive,
        position = position,
        colorHex = colorHex,
    )

    companion object {
        val PRESET_COLORS = listOf(
            "#F44336", "#E91E63", "#9C27B0", "#673AB7",
            "#3F51B5", "#2196F3", "#03A9F4", "#00BCD4",
            "#009688", "#4CAF50", "#8BC34A", "#CDDC39",
            "#FF9800", "#FF5722", "#795548", "#9E9E9E",
        )

        fun fromEntity(entity: BudgetCategoryEntity): BudgetCategory = BudgetCategory(
            id = entity.id,
            name = entity.name,
            parentId = entity.parentId,
            budgetId = entity.budgetId,
            plannedAmount = entity.plannedAmount,
            currentBalance = entity.currentBalance,
            defaultIncomeAmount = entity.defaultIncomeAmount,
            defaultPlannedAmount = entity.defaultPlannedAmount,
            isActive = entity.isActive,
            position = entity.position,
            colorHex = entity.colorHex,
        )
    }
}
