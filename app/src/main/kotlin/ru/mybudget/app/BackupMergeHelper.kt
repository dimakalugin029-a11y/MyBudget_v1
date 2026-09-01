package ru.mybudget.app

import ru.mybudget.app.data.BudgetCategoryEntity
import ru.mybudget.app.data.BudgetProfileEntity
import ru.mybudget.app.data.UtilityBillEntity
import ru.mybudget.app.data.UtilityPropertyEntity

internal object BackupMergeHelper {
    fun profileKey(profile: BudgetProfileEntity): String = profile.name.trim().lowercase()

    fun categoryKey(category: BudgetCategoryEntity): String {
        return "${category.budgetId}|${category.parentId}|${category.name.trim().lowercase()}"
    }

    fun propertyKey(property: UtilityPropertyEntity): String = property.name.trim().lowercase()

    fun billKey(propertyId: Int, bill: UtilityBillEntity): String =
        "$propertyId|${bill.year}|${bill.month}"
}
