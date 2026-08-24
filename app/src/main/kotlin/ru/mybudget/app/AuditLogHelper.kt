package ru.mybudget.app

import org.json.JSONObject
import ru.mybudget.app.data.AuditActionEntity
import ru.mybudget.app.data.AuditActionType
import ru.mybudget.app.data.BudgetRepository

object AuditLogHelper {
    data class CategoryDeletePayload(
        val categoryId: Int,
        val categoryName: String,
        val parentId: Int,
        val budgetId: Int,
        val balance: Double,
        val targetCategoryId: Int?,
        val targetCategoryName: String?,
    )

    fun encodeCategoryDelete(payload: CategoryDeletePayload): String {
        return JSONObject().apply {
            put("categoryId", payload.categoryId)
            put("categoryName", payload.categoryName)
            put("parentId", payload.parentId)
            put("budgetId", payload.budgetId)
            put("balance", payload.balance)
            if (payload.targetCategoryId == null) {
                put("targetCategoryId", JSONObject.NULL)
            } else {
                put("targetCategoryId", payload.targetCategoryId)
            }
            put("targetCategoryName", payload.targetCategoryName.orEmpty())
        }.toString()
    }

    fun decodeCategoryDelete(json: String): CategoryDeletePayload? {
        return try {
            val obj = JSONObject(json)
            val targetName = obj.optString("targetCategoryName")
            CategoryDeletePayload(
                categoryId = obj.getInt("categoryId"),
                categoryName = obj.getString("categoryName"),
                parentId = obj.getInt("parentId"),
                budgetId = obj.getInt("budgetId"),
                balance = obj.getDouble("balance"),
                targetCategoryId = if (obj.isNull("targetCategoryId")) null else obj.getInt("targetCategoryId"),
                targetCategoryName = targetName.takeIf { it.isNotBlank() },
            )
        } catch (_: Exception) {
            null
        }
    }

    suspend fun recordCategoryDeleted(repository: BudgetRepository, payload: CategoryDeletePayload) {
        val target = payload.targetCategoryName?.let { " → $it" }.orEmpty()
        repository.insertAuditAction(
            AuditActionEntity(
                actionType = AuditActionType.CATEGORY_DELETED,
                title = "Удалена подстатья",
                description = "${payload.categoryName}$target · ${MoneyFormat.formatRub(payload.balance)}",
                payload = encodeCategoryDelete(payload),
            ),
        )
    }

    suspend fun undoCategoryDeleted(
        repository: BudgetRepository,
        budgetManager: BudgetManager,
        action: AuditActionEntity,
    ): Boolean {
        val payload = decodeCategoryDelete(action.payload) ?: return false
        repository.restoreCategory(payload.categoryId)
        val targetId = payload.targetCategoryId
        if (targetId != null && payload.balance != 0.0) {
            repository.transferBetweenLeafCategories(targetId, payload.categoryId, payload.balance)
        }
        repository.markAuditReverted(action.id)
        budgetManager.reloadCategoriesFromDatabase()
        return true
    }
}
