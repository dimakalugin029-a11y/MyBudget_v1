package ru.mybudget.app.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "budget_profiles")
data class BudgetProfileEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    val sortOrder: Int = 0,
    val isActive: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "categories",
    foreignKeys = [
        ForeignKey(
            entity = BudgetProfileEntity::class,
            parentColumns = ["id"],
            childColumns = ["budgetId"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [Index("budgetId")],
)
data class BudgetCategoryEntity(
    @PrimaryKey val id: Int,
    val name: String,
    val parentId: Int,
    @ColumnInfo(defaultValue = "1") val budgetId: Int = 1,
    val plannedAmount: Double = 0.0,
    val currentBalance: Double = 0.0,
    val defaultIncomeAmount: Double = 0.0,
    val defaultPlannedAmount: Double = 0.0,
    val isActive: Boolean = true,
    val position: Int = 0,
    val colorHex: String = "",
)

@Entity(
    tableName = "transactions",
    foreignKeys = [
        ForeignKey(
            entity = BudgetCategoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["categoryId"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [Index("categoryId"), Index("groupId")],
)
data class TransactionEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val categoryId: Int,
    val amount: Double,
    val type: String,
    val description: String,
    val date: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "NULL") val groupId: String? = null,
    val participantLabel: String = "",
)

@Entity(
    tableName = "payment_reminders",
    foreignKeys = [
        ForeignKey(
            entity = BudgetCategoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["categoryId"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [Index("categoryId"), Index("obligationId")],
)
data class PaymentReminderEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val title: String,
    val amount: Double,
    val categoryId: Int,
    val dueDate: String,
    val repeatType: String,
    val isActive: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "NULL") val obligationId: Int? = null,
) {
    fun toPaymentReminder(categoryName: String = ""): PaymentReminder {
        val parsedDue = runCatching {
            java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).parse(dueDate)
        }.getOrNull() ?: java.util.Date()
        return PaymentReminder(
            id = id.toLong(),
            title = title,
            amount = amount,
            categoryId = categoryId.toLong(),
            categoryName = categoryName,
            dueDate = parsedDue,
            repeatType = repeatType,
            isCompleted = !isActive,
            obligationId = obligationId ?: 0,
        )
    }
}

@Entity(
    tableName = "savings_goals",
    foreignKeys = [
        ForeignKey(
            entity = BudgetCategoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["categoryId"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [Index("categoryId")],
)
data class SavingsGoalEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    val targetAmount: Double,
    val categoryId: Int,
    val deadline: String? = null,
    val isActive: Boolean = true,
)

@Entity(
    tableName = "recurring_transactions",
    foreignKeys = [
        ForeignKey(
            entity = BudgetCategoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["categoryId"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [Index("categoryId"), Index("obligationId")],
)
data class RecurringTransactionEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val categoryId: Int,
    val amount: Double,
    val type: String,
    val description: String = "",
    val repeatType: String,
    val nextDueDate: String,
    val isActive: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "NULL") val obligationId: Int? = null,
)

@Entity(
    tableName = "planned_obligations",
    foreignKeys = [
        ForeignKey(
            entity = BudgetProfileEntity::class,
            parentColumns = ["id"],
            childColumns = ["budgetId"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [Index("budgetId")],
)
data class PlannedObligationEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val budgetId: Int,
    val name: String,
    val amount: Double,
    val periodType: String,
    val categoryId: Int,
    val paychecksPerMonth: Int,
    val dueMonth: Int,
    val dueDay: Int = 0,
    val note: String = "",
    val isActive: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "0") val remindEnabled: Boolean = false,
    @ColumnInfo(defaultValue = "0") val autoPostEnabled: Boolean = false,
    @ColumnInfo(defaultValue = "'other'") val obligationKind: String = "other",
    @ColumnInfo(defaultValue = "NULL") val linkedIncomeSourceId: Int? = null,
)

@Entity(
    tableName = "obligation_payments",
    foreignKeys = [
        ForeignKey(
            entity = PlannedObligationEntity::class,
            parentColumns = ["id"],
            childColumns = ["obligationId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("obligationId"),
        Index(value = ["obligationId", "periodYear", "periodMonth"], unique = true),
    ],
)
data class ObligationPaymentEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val obligationId: Int,
    val periodYear: Int,
    val periodMonth: Int,
    val amount: Double,
    val paidAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "balance_snapshots",
    foreignKeys = [
        ForeignKey(
            entity = BudgetProfileEntity::class,
            parentColumns = ["id"],
            childColumns = ["budgetId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("budgetId"),
        Index("dayKey"),
        Index(value = ["budgetId", "dayKey"], unique = true),
    ],
)
data class BalanceSnapshotEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val budgetId: Int,
    val dayKey: Long,
    val totalBalance: Double,
    val recordedAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "planned_income_sources",
    foreignKeys = [
        ForeignKey(
            entity = BudgetProfileEntity::class,
            parentColumns = ["id"],
            childColumns = ["budgetId"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [Index("budgetId")],
)
data class PlannedIncomeSourceEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val budgetId: Int,
    val name: String,
    val amount: Double,
    val sourceType: String,
    @ColumnInfo(defaultValue = "0") val dayOfMonth: Int = 0,
    @ColumnInfo(defaultValue = "'monthly'") val periodType: String = "monthly",
    @ColumnInfo(defaultValue = "1") val dueMonth: Int = 1,
    @ColumnInfo(defaultValue = "0") val sortOrder: Int = 0,
    val isActive: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "monthly_category_plans",
    primaryKeys = ["year", "month", "categoryId"],
)
data class MonthlyCategoryPlanEntity(
    val year: Int,
    val month: Int,
    val categoryId: Int,
    val budgetId: Int,
    val plannedAmount: Double = 0.0,
    val isEnabled: Boolean = true,
)

@Entity(tableName = "audit_actions")
data class AuditActionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val actionType: String,
    val title: String,
    val description: String,
    val payload: String,
    val createdAt: Long = System.currentTimeMillis(),
    val isReverted: Boolean = false,
    val revertedAt: Long? = null,
)

object AuditActionType {
    const val CATEGORY_DELETED = "category_deleted"
    const val TRANSACTION_CANCELLED = "transaction_cancelled"
}

data class CategoryExpenseSum(
    val categoryId: Int,
    val total: Double,
)
