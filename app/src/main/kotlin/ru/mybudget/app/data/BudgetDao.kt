package ru.mybudget.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
abstract class BudgetDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertBudgetProfile(profile: BudgetProfileEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertCategory(category: BudgetCategoryEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertAuditAction(action: AuditActionEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertMonthlyPlan(plan: MonthlyCategoryPlanEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertTransaction(transaction: TransactionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertReminder(reminder: PaymentReminderEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertSavingsGoal(goal: SavingsGoalEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertRecurring(r: RecurringTransactionEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertPlannedObligation(obligation: PlannedObligationEntity): Long

    @Update
    abstract suspend fun updateBudgetProfile(profile: BudgetProfileEntity)

    @Update
    abstract suspend fun updateCategory(category: BudgetCategoryEntity)

    @Update
    abstract suspend fun updateTransaction(transaction: TransactionEntity)

    @Update
    abstract suspend fun updateReminder(reminder: PaymentReminderEntity)

    @Update
    abstract suspend fun updateSavingsGoal(goal: SavingsGoalEntity)

    @Update
    abstract suspend fun updateRecurring(r: RecurringTransactionEntity)

    @Update
    abstract suspend fun updatePlannedObligation(obligation: PlannedObligationEntity)

    @Query("SELECT * FROM budget_profiles WHERE isActive = 1 ORDER BY sortOrder, id")
    abstract fun getAllBudgetProfiles(): Flow<List<BudgetProfileEntity>>

    @Query("SELECT * FROM budget_profiles WHERE isActive = 1 ORDER BY sortOrder, id")
    abstract suspend fun getAllBudgetProfilesOnce(): List<BudgetProfileEntity>

    @Query("SELECT * FROM budget_profiles ORDER BY sortOrder, id")
    abstract suspend fun getAllBudgetProfilesForExport(): List<BudgetProfileEntity>

    @Query("SELECT * FROM budget_profiles WHERE id = :id")
    abstract suspend fun getBudgetProfileById(id: Int): BudgetProfileEntity?

    @Query("SELECT COUNT(*) FROM budget_profiles")
    abstract suspend fun getBudgetProfileCount(): Int

    @Query("SELECT COALESCE(MAX(sortOrder), -1) FROM budget_profiles")
    abstract suspend fun getMaxBudgetProfileSortOrder(): Int

    @Query("SELECT COALESCE(MAX(id), 0) FROM categories")
    abstract suspend fun getMaxCategoryId(): Int

    @Query("SELECT * FROM categories WHERE isActive = 1 ORDER BY parentId, id")
    abstract fun getAllCategories(): Flow<List<BudgetCategoryEntity>>

    @Query("SELECT * FROM categories WHERE isActive = 1 AND budgetId = :budgetId ORDER BY parentId, id")
    abstract fun getCategoriesByBudget(budgetId: Int): Flow<List<BudgetCategoryEntity>>

    @Query("SELECT * FROM categories WHERE isActive = 1 AND budgetId = :budgetId ORDER BY parentId, id")
    abstract suspend fun getCategoriesByBudgetOnce(budgetId: Int): List<BudgetCategoryEntity>

    @Query("SELECT * FROM categories ORDER BY parentId, id")
    abstract suspend fun getAllCategoriesForExport(): List<BudgetCategoryEntity>

    @Query("SELECT * FROM categories WHERE id = :id AND isActive = 1")
    abstract suspend fun getCategoryById(id: Int): BudgetCategoryEntity?

    @Query("SELECT * FROM categories WHERE parentId = :parentId AND isActive = 1")
    abstract fun getSubCategories(parentId: Int): Flow<List<BudgetCategoryEntity>>

    @Query("SELECT * FROM categories WHERE parentId = :parentId AND isActive = 1")
    abstract suspend fun getSubCategoriesOnce(parentId: Int): List<BudgetCategoryEntity>

    @Query("SELECT * FROM categories WHERE id = :id")
    abstract suspend fun getCategoryByIdAny(id: Int): BudgetCategoryEntity?

    @Query("SELECT * FROM audit_actions WHERE isReverted = 0 ORDER BY createdAt DESC LIMIT :limit")
    abstract suspend fun getActiveAuditActions(limit: Int): List<AuditActionEntity>

    @Query("SELECT * FROM audit_actions WHERE id = :id")
    abstract suspend fun getAuditActionById(id: Long): AuditActionEntity?

    @Query(
        """
        SELECT * FROM monthly_category_plans
        WHERE budgetId = :budgetId AND year = :year AND month = :month
    """,
    )
    abstract suspend fun getMonthlyPlansForBudgetMonth(
        budgetId: Int,
        year: Int,
        month: Int,
    ): List<MonthlyCategoryPlanEntity>

    @Query(
        """
        SELECT COALESCE(SUM(amount), 0) FROM transactions
        WHERE categoryId = :categoryId AND type = 'expense'
        AND date >= :startMs AND date < :endMs
    """,
    )
    abstract suspend fun getExpenseSumForCategoryInRange(
        categoryId: Int,
        startMs: Long,
        endMs: Long,
    ): Double

    @Query("SELECT * FROM transactions ORDER BY date DESC")
    abstract fun getAllTransactions(): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions WHERE categoryId = :categoryId ORDER BY date DESC")
    abstract fun getTransactionsByCategory(categoryId: Int): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions WHERE categoryId IN (:categoryIds) ORDER BY date DESC")
    abstract fun getTransactionsByCategoryIds(categoryIds: List<Int>): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions WHERE groupId = :groupId ORDER BY id ASC")
    abstract suspend fun getTransactionsByGroupId(groupId: String): List<TransactionEntity>

    @Query("SELECT COUNT(*) FROM transactions WHERE groupId = :groupId")
    abstract suspend fun countTransactionsByGroupId(groupId: String): Int

    @Query(
        """
        SELECT COUNT(*) FROM transactions
        WHERE type = 'expense' AND description = :description
        """,
    )
    abstract suspend fun countExpenseTransactionsByDescription(description: String): Int

    @Query(
        """
        SELECT * FROM transactions
        WHERE type = 'expense' AND description = :description
        """,
    )
    abstract suspend fun getExpenseTransactionsByDescription(description: String): List<TransactionEntity>

    @Query("SELECT * FROM transactions WHERE id = :transactionId")
    abstract suspend fun getTransactionById(transactionId: Int): TransactionEntity?

    @Query("SELECT SUM(amount) FROM transactions WHERE categoryId = :categoryId AND type = 'income'")
    abstract suspend fun getTotalIncomeForCategory(categoryId: Int): Double?

    @Query("SELECT SUM(amount) FROM transactions WHERE categoryId = :categoryId AND type = 'expense'")
    abstract suspend fun getTotalExpenseForCategory(categoryId: Int): Double?

    @Query("SELECT * FROM payment_reminders WHERE isActive = 1 ORDER BY dueDate")
    abstract fun getAllActiveReminders(): Flow<List<PaymentReminderEntity>>

    @Query("SELECT * FROM payment_reminders WHERE isActive = 1 ORDER BY dueDate")
    abstract fun getAllReminders(): Flow<List<PaymentReminderEntity>>

    @Query("SELECT * FROM payment_reminders")
    abstract suspend fun getAllRemindersForExport(): List<PaymentReminderEntity>

    @Query("SELECT * FROM payment_reminders WHERE id = :id")
    abstract suspend fun getReminderById(id: Int): PaymentReminderEntity?

    @Query("SELECT * FROM payment_reminders WHERE isActive = 1 AND dueDate = :today")
    abstract suspend fun getRemindersDueToday(today: String): List<PaymentReminderEntity>

    @Query("SELECT * FROM payment_reminders WHERE isActive = 1 AND dueDate >= :today ORDER BY dueDate LIMIT :limit")
    abstract suspend fun getUpcomingReminders(today: String, limit: Int): List<PaymentReminderEntity>

    @Query("SELECT * FROM payment_reminders WHERE isActive = 1 AND dueDate >= :from AND dueDate <= :to ORDER BY dueDate")
    abstract suspend fun getRemindersInRange(from: String, to: String): List<PaymentReminderEntity>

    @Query("SELECT * FROM recurring_transactions WHERE isActive = 1 AND nextDueDate >= :from AND nextDueDate <= :to ORDER BY nextDueDate")
    abstract suspend fun getRecurringInRange(from: String, to: String): List<RecurringTransactionEntity>

    @Query("SELECT COALESCE(MAX(position), -1) FROM categories WHERE parentId = :parentId AND isActive = 1")
    abstract suspend fun getMaxPositionForParent(parentId: Int): Int

    @Query("SELECT * FROM savings_goals WHERE isActive = 1 ORDER BY name")
    abstract fun getAllSavingsGoals(): Flow<List<SavingsGoalEntity>>

    @Query("SELECT * FROM savings_goals")
    abstract suspend fun getAllSavingsGoalsForExport(): List<SavingsGoalEntity>

    @Query("SELECT * FROM recurring_transactions WHERE isActive = 1 AND nextDueDate <= :today")
    abstract suspend fun getRecurringDueBy(today: String): List<RecurringTransactionEntity>

    @Query("SELECT * FROM recurring_transactions WHERE id = :id LIMIT 1")
    abstract suspend fun getRecurringById(id: Int): RecurringTransactionEntity?

    @Query("SELECT * FROM recurring_transactions WHERE isActive = 1 ORDER BY nextDueDate")
    abstract fun getAllRecurring(): Flow<List<RecurringTransactionEntity>>

    @Query("SELECT * FROM recurring_transactions ORDER BY id")
    abstract suspend fun getAllRecurringForExport(): List<RecurringTransactionEntity>

    @Query("SELECT * FROM planned_obligations WHERE isActive = 1 AND budgetId = :budgetId ORDER BY periodType, name")
    abstract fun getPlannedObligationsByBudget(budgetId: Int): Flow<List<PlannedObligationEntity>>

    @Query("SELECT * FROM planned_obligations WHERE isActive = 1 AND budgetId = :budgetId ORDER BY periodType, name")
    abstract suspend fun getPlannedObligationsByBudgetOnce(budgetId: Int): List<PlannedObligationEntity>

    @Query("SELECT * FROM planned_obligations ORDER BY id")
    abstract suspend fun getAllPlannedObligationsForExport(): List<PlannedObligationEntity>

    @Query("SELECT * FROM planned_obligations WHERE id = :id LIMIT 1")
    abstract suspend fun getPlannedObligationById(id: Int): PlannedObligationEntity?

    @Query(
        """
        SELECT COALESCE(SUM(amount), 0) FROM transactions
        WHERE categoryId = :categoryId AND type = 'expense' AND date >= :startMs
    """,
    )
    abstract suspend fun getExpenseSumForCategorySince(categoryId: Int, startMs: Long): Double

    @Query(
        """
        SELECT categoryId, COALESCE(SUM(amount), 0) AS total FROM transactions
        WHERE type = 'expense' AND date >= :startMs
        GROUP BY categoryId
    """,
    )
    abstract suspend fun getExpenseSumsSince(startMs: Long): List<CategoryExpenseSum>

    @Query("UPDATE budget_profiles SET isActive = 0 WHERE id = :id")
    abstract suspend fun deactivateBudgetProfile(id: Int)

    @Query("DELETE FROM budget_profiles")
    abstract suspend fun deleteAllBudgetProfiles()

    @Query("UPDATE categories SET isActive = 0 WHERE id = :categoryId")
    abstract suspend fun deleteCategory(categoryId: Int)

    @Query("UPDATE categories SET isActive = 1 WHERE id = :categoryId")
    abstract suspend fun restoreCategory(categoryId: Int)

    @Query("UPDATE audit_actions SET isReverted = 1, revertedAt = :revertedAt WHERE id = :id")
    abstract suspend fun markAuditReverted(id: Long, revertedAt: Long)

    @Query("UPDATE categories SET defaultIncomeAmount = :amount WHERE id = :categoryId")
    abstract suspend fun updateDefaultIncomeAmount(categoryId: Int, amount: Double)

    @Query("UPDATE categories SET defaultPlannedAmount = :amount WHERE id = :categoryId")
    abstract suspend fun updateDefaultPlannedAmount(categoryId: Int, amount: Double)

    @Query("DELETE FROM transactions WHERE groupId = :groupId")
    abstract suspend fun deleteTransactionsByGroupId(groupId: String)

    @Query("DELETE FROM transactions WHERE id = :transactionId")
    abstract suspend fun deleteTransaction(transactionId: Int)

    @Query("DELETE FROM payment_reminders")
    abstract suspend fun deleteAllReminders()

    @Query("UPDATE payment_reminders SET isActive = 0 WHERE id = :id")
    abstract suspend fun deleteReminder(id: Int)

    @Query("UPDATE payment_reminders SET dueDate = :newDueDate WHERE id = :id")
    abstract suspend fun updateReminderDueDate(id: Int, newDueDate: String)

    @Query("DELETE FROM categories")
    abstract suspend fun deleteAllCategories()

    @Query("DELETE FROM transactions")
    abstract suspend fun deleteAllTransactions()

    @Query("UPDATE categories SET position = :position WHERE id = :categoryId")
    abstract suspend fun updateCategoryPosition(categoryId: Int, position: Int)

    @Query("DELETE FROM savings_goals")
    abstract suspend fun deleteAllSavingsGoals()

    @Query("UPDATE savings_goals SET isActive = 0 WHERE id = :id")
    abstract suspend fun deleteSavingsGoal(id: Int)

    @Query("UPDATE recurring_transactions SET isActive = 0 WHERE id = :id")
    abstract suspend fun deleteRecurring(id: Int)

    @Query("UPDATE recurring_transactions SET nextDueDate = :next WHERE id = :id")
    abstract suspend fun updateRecurringNextDate(id: Int, next: String)

    @Query("DELETE FROM recurring_transactions")
    abstract suspend fun deleteAllRecurring()

    @Query("UPDATE planned_obligations SET isActive = 0 WHERE id = :id")
    abstract suspend fun deletePlannedObligation(id: Int)

    @Query("DELETE FROM planned_obligations")
    abstract suspend fun deleteAllPlannedObligations()

    @Query("DELETE FROM monthly_category_plans")
    abstract suspend fun deleteAllMonthlyPlans()

    @Query("DELETE FROM audit_actions")
    abstract suspend fun deleteAllAuditActions()

    @Transaction
    open suspend fun applyAmountToCategoryAndUpdateParent(categoryId: Int, amount: Double) {
        recordTransaction(
            categoryId = categoryId,
            amount = kotlin.math.abs(amount),
            type = if (amount > 0.0) "income" else "expense",
            description = "",
            groupId = null,
            date = System.currentTimeMillis(),
        )
    }

    @Transaction
    open suspend fun applyBalanceDelta(categoryId: Int, amount: Double) {
        val category = getCategoryById(categoryId) ?: return
        updateCategory(category.copy(currentBalance = category.currentBalance + amount))
        if (category.parentId == 0) return
        val parent = getCategoryById(category.parentId) ?: return
        updateCategory(parent.copy(currentBalance = parent.currentBalance + amount))
    }

    @Transaction
    open suspend fun recordTransaction(
        categoryId: Int,
        amount: Double,
        type: String,
        description: String,
        groupId: String? = null,
        date: Long = System.currentTimeMillis(),
    ) {
        insertTransaction(
            TransactionEntity(
                categoryId = categoryId,
                amount = amount,
                type = type,
                description = description,
                date = date,
                groupId = groupId,
            ),
        )
        val delta = if (type == "income") amount else -amount
        applyBalanceDelta(categoryId, delta)
    }

    @Transaction
    open suspend fun revertAndDeleteTransaction(transaction: TransactionEntity) {
        val delta = if (transaction.type == "income") -transaction.amount else transaction.amount
        applyBalanceDelta(transaction.categoryId, delta)
        deleteTransaction(transaction.id)
    }

    @Transaction
    open suspend fun cancelTransactionGroup(groupId: String) {
        val txs = getTransactionsByGroupId(groupId)
        for (transaction in txs) {
            val delta = if (transaction.type == "income") -transaction.amount else transaction.amount
            applyBalanceDelta(transaction.categoryId, delta)
        }
        deleteTransactionsByGroupId(groupId)
    }

    @Transaction
    open suspend fun applyTransactionGroup(
        categoryIds: IntArray,
        amounts: DoubleArray,
        type: String,
        description: String,
        groupId: String,
        date: Long,
    ) {
        for (index in categoryIds.indices) {
            recordTransaction(
                categoryId = categoryIds[index],
                amount = amounts[index],
                type = type,
                description = description,
                groupId = groupId,
                date = date,
            )
        }
    }

    @Transaction
    open suspend fun transferBetweenLeafCategories(fromId: Int, toId: Int, amount: Double) {
        applyBalanceDelta(fromId, -amount)
        applyBalanceDelta(toId, amount)
    }
}
