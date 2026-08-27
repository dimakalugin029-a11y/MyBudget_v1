package ru.mybudget.app.utilities

import android.content.Context
import ru.mybudget.app.BudgetCategory
import ru.mybudget.app.BudgetManager
import ru.mybudget.app.CategoryMultiPicker
import ru.mybudget.app.setup.UtilitySetupPreferences

object UtilityPayCategoryHelper {
    data class CategoryOption(
        val category: BudgetCategory,
        val label: String,
    )

    suspend fun loadLeafOptions(manager: BudgetManager, budgetId: Int): List<CategoryOption> {
        manager.getCategoriesAsync()
        val parents = manager.getRootCategories(budgetId).associate { it.id to it.name }
        return manager.getCategoriesForBudget(budgetId)
            .filter { !manager.hasSubcategories(it.id) }
            .map { CategoryOption(it, CategoryMultiPicker.leafLabel(it, parents)) }
    }

    fun primarySpinnerIndex(options: List<CategoryOption>, context: Context, propertyId: Int): Int {
        val saved = UtilitySetupPreferences.getPayPrimaryCategoryId(context, propertyId)
        val index = options.indexOfFirst { it.category.id == saved }
        return if (index >= 0) index else 0
    }

    fun extraSpinnerIndex(
        options: List<CategoryOption>,
        context: Context,
        propertyId: Int,
        primaryIndex: Int,
    ): Int {
        val saved = UtilitySetupPreferences.getPayExtraCategoryId(context, propertyId)
        val index = options.indexOfFirst { it.category.id == saved }
        if (index >= 0 && index != primaryIndex) return index
        return options.indices.firstOrNull { it != primaryIndex } ?: primaryIndex
    }

    fun rememberSelection(
        context: Context,
        propertyId: Int,
        primary: BudgetCategory,
        extra: BudgetCategory?,
    ) {
        UtilitySetupPreferences.setPayPrimaryCategoryId(context, primary.id, propertyId)
        if (extra != null) {
            UtilitySetupPreferences.setPayExtraCategoryId(context, extra.id, propertyId)
        }
    }
}
