package ru.mybudget.app.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import ru.mybudget.app.BudgetApplication
import ru.mybudget.app.BudgetCategory
import ru.mybudget.app.MoneyFormat
import ru.mybudget.app.OverspendNotifier

class BudgetRepository(private val budgetDao: BudgetDao) {

    suspend fun ensureDefaultBudgetProfile() {
        if (budgetDao.getBudgetProfileCount() == 0) {
            budgetDao.insertBudgetProfile(
                BudgetProfileEntity(
                    id = 1,
                    name = "Основной",
                    sortOrder = 0,
                    isActive = true,
                ),
            )
        }
    }

    fun getAllBudgetProfiles(): Flow<List<BudgetProfileEntity>> = budgetDao.getAllBudgetProfiles()

    suspend fun getAllBudgetProfilesOnce(): List<BudgetProfileEntity> = budgetDao.getAllBudgetProfilesOnce()

    suspend fun getBudgetProfileById(id: Int): BudgetProfileEntity? = budgetDao.getBudgetProfileById(id)

    suspend fun insertBudgetProfile(name: String): Int {
        val sortOrder = budgetDao.getMaxBudgetProfileSortOrder() + 1
        return budgetDao.insertBudgetProfile(
            BudgetProfileEntity(name = name, sortOrder = sortOrder),
        ).toInt()
    }

    suspend fun updateBudgetProfile(profile: BudgetProfileEntity) = budgetDao.updateBudgetProfile(profile)

    suspend fun deactivateBudgetProfile(id: Int) = budgetDao.deactivateBudgetProfile(id)

    suspend fun getCategoriesByBudgetOnce(budgetId: Int): List<BudgetCategory> {
        return budgetDao.getCategoriesByBudgetOnce(budgetId).map { BudgetCategory.fromEntity(it) }
    }

    suspend fun calculateBudgetTotal(budgetId: Int): Double {
        return getCategoriesByBudgetOnce(budgetId)
            .filter { it.parentId == 0 && it.isActive }
            .sumOf { it.currentBalance }
    }

    fun getAllCategories(): Flow<List<BudgetCategory>> {
        return budgetDao.getAllCategories().map { list -> list.map { BudgetCategory.fromEntity(it) } }
    }

    fun getSubCategories(parentId: Int): Flow<List<BudgetCategory>> {
        return budgetDao.getSubCategories(parentId).map { list -> list.map { BudgetCategory.fromEntity(it) } }
    }

    suspend fun getCategoryById(id: Int): BudgetCategory? {
        return budgetDao.getCategoryById(id)?.let { BudgetCategory.fromEntity(it) }
    }

    suspend fun getMaxCategoryId(): Int = budgetDao.getMaxCategoryId()

    suspend fun insertCategory(category: BudgetCategory) = budgetDao.insertCategory(category.toEntity())

    suspend fun updateCategory(category: BudgetCategory) = budgetDao.updateCategory(category.toEntity())

    suspend fun deleteCategory(categoryId: Int) = budgetDao.deleteCategory(categoryId)

    suspend fun updateDefaultIncomeAmount(categoryId: Int, amount: Double) {
        budgetDao.updateDefaultIncomeAmount(categoryId, amount)
    }

    suspend fun updateDefaultPlannedAmount(categoryId: Int, amount: Double) {
        budgetDao.updateDefaultPlannedAmount(categoryId, amount)
    }

    suspend fun getMonthlyPlansForBudgetMonth(
        budgetId: Int,
        year: Int,
        month: Int,
    ): List<MonthlyCategoryPlanEntity> = budgetDao.getMonthlyPlansForBudgetMonth(budgetId, year, month)

    suspend fun upsertMonthlyPlan(plan: MonthlyCategoryPlanEntity) = budgetDao.upsertMonthlyPlan(plan)

    suspend fun getExpenseSumForCategoryInRange(
        categoryId: Int,
        startMs: Long,
        endMs: Long,
    ): Double = budgetDao.getExpenseSumForCategoryInRange(categoryId, startMs, endMs)

    suspend fun recordTransaction(
        categoryId: Int,
        amount: Double,
        type: String,
        description: String,
        groupId: String? = null,
        date: Long = System.currentTimeMillis(),
    ) {
        budgetDao.recordTransaction(categoryId, amount, type, description, groupId, date)
        if (type == "expense") {
            OverspendNotifier.checkAfterExpense(BudgetApplication.instance, categoryId)
        }
    }

    suspend fun applyTransactionGroup(
        items: List<Pair<Int, Double>>,
        type: String,
        description: String,
        groupId: String,
        date: Long = System.currentTimeMillis(),
    ) {
        budgetDao.applyTransactionGroup(
            categoryIds = items.map { it.first }.toIntArray(),
            amounts = items.map { it.second }.toDoubleArray(),
            type = type,
            description = description,
            groupId = groupId,
            date = date,
        )
        if (type == "expense") {
            val app = BudgetApplication.instance
            for ((categoryId, _) in items) {
                OverspendNotifier.checkAfterExpense(app, categoryId)
            }
        }
    }

    suspend fun addTransaction(
        categoryId: Int,
        amount: Double,
        type: String,
        description: String,
    ) {
        recordTransaction(categoryId, amount, type, description)
    }

    suspend fun applyAmountTransactional(categoryId: Int, amount: Double) {
        budgetDao.applyAmountToCategoryAndUpdateParent(categoryId, amount)
    }

    suspend fun applyBalanceDelta(categoryId: Int, amount: Double) {
        budgetDao.applyBalanceDelta(categoryId, amount)
    }

    suspend fun transferBetweenLeafCategories(fromId: Int, toId: Int, amount: Double) {
        budgetDao.transferBetweenLeafCategories(fromId, toId, amount)
    }

    suspend fun getCategoryBalance(categoryId: Int): Double {
        return budgetDao.getCategoryById(categoryId)?.currentBalance ?: 0.0
    }

    fun getAllTransactions(): Flow<List<TransactionEntity>> = budgetDao.getAllTransactions()

    fun getTransactionsByCategoryIds(categoryIds: List<Int>): Flow<List<TransactionEntity>> {
        return budgetDao.getTransactionsByCategoryIds(categoryIds)
    }

    suspend fun getTransactionsByGroupId(groupId: String): List<TransactionEntity> {
        return budgetDao.getTransactionsByGroupId(groupId)
    }

    suspend fun getTransactionById(transactionId: Int): TransactionEntity? {
        return budgetDao.getTransactionById(transactionId)
    }

    suspend fun updateTransaction(transaction: TransactionEntity) = budgetDao.updateTransaction(transaction)

    suspend fun deleteTransaction(transactionId: Int) {
        val transaction = budgetDao.getTransactionById(transactionId) ?: return
        budgetDao.revertAndDeleteTransaction(transaction)
    }

    suspend fun cancelTransaction(transaction: TransactionEntity) {
        budgetDao.revertAndDeleteTransaction(transaction)
    }

    suspend fun correctTransaction(
        original: TransactionEntity,
        newCategoryId: Int,
        newAmount: Double,
        newType: String,
        newDescription: String,
        newParticipantLabel: String = original.participantLabel,
    ) {
        val rounded = MoneyFormat.roundMoney(newAmount)
        val revert = if (original.type == "income") -original.amount else original.amount
        budgetDao.applyBalanceDelta(original.categoryId, revert)
        val apply = if (newType == "income") rounded else -rounded
        budgetDao.applyBalanceDelta(newCategoryId, apply)
        budgetDao.updateTransaction(
            original.copy(
                categoryId = newCategoryId,
                amount = rounded,
                type = newType,
                description = newDescription,
                participantLabel = newParticipantLabel.trim(),
            ),
        )
    }

    suspend fun cancelTransactionGroup(groupId: String) = budgetDao.cancelTransactionGroup(groupId)

    suspend fun getExpenseTransactionsByDescription(description: String): List<TransactionEntity> {
        return budgetDao.getExpenseTransactionsByDescription(description)
    }

    suspend fun getCategoryName(categoryId: Int): String {
        return budgetDao.getCategoryByIdAny(categoryId)?.name.orEmpty()
    }

    suspend fun getAllTransactionsForPeriod(): List<TransactionEntity> {
        return emptyList()
    }

    fun getAllReminders(): Flow<List<PaymentReminder>> {
        return budgetDao.getAllReminders().map { list -> list.map { it.toPaymentReminder() } }
    }

    suspend fun insertReminder(reminder: PaymentReminder) {
        budgetDao.insertReminder(reminder.toPaymentReminderEntity())
    }

    suspend fun updateReminder(reminder: PaymentReminder) {
        budgetDao.updateReminder(reminder.toPaymentReminderEntity())
    }

    suspend fun deleteReminder(id: Long) = budgetDao.deleteReminder(id.toInt())

    suspend fun getReminderById(id: Int): PaymentReminderEntity? = budgetDao.getReminderById(id)

    suspend fun deleteAllCategories() = budgetDao.deleteAllCategories()

    suspend fun deleteAllTransactions() = budgetDao.deleteAllTransactions()

    suspend fun updateReminderDueDate(id: Long, newDueDate: String) {
        budgetDao.updateReminderDueDate(id.toInt(), newDueDate)
    }

    suspend fun getMaxPositionForParent(parentId: Int): Int = budgetDao.getMaxPositionForParent(parentId)

    suspend fun updateCategoryPosition(categoryId: Int, position: Int) {
        budgetDao.updateCategoryPosition(categoryId, position)
    }

    fun getAllSavingsGoals(): Flow<List<SavingsGoalEntity>> = budgetDao.getAllSavingsGoals()

    suspend fun insertSavingsGoal(goal: SavingsGoalEntity) = budgetDao.insertSavingsGoal(goal)

    suspend fun updateSavingsGoal(goal: SavingsGoalEntity) = budgetDao.updateSavingsGoal(goal)

    suspend fun deleteSavingsGoal(id: Int) = budgetDao.deleteSavingsGoal(id)

    suspend fun getRecurringDueBy(today: String): List<RecurringTransactionEntity> {
        return budgetDao.getRecurringDueBy(today)
    }

    fun getAllRecurring(): Flow<List<RecurringTransactionEntity>> = budgetDao.getAllRecurring()

    suspend fun insertRecurring(r: RecurringTransactionEntity) = budgetDao.insertRecurring(r)

    suspend fun updateRecurringNextDate(id: Int, next: String) = budgetDao.updateRecurringNextDate(id, next)

    suspend fun deleteRecurring(id: Int) = budgetDao.deleteRecurring(id)

    fun getPlannedObligationsByBudget(budgetId: Int): Flow<List<PlannedObligationEntity>> {
        return budgetDao.getPlannedObligationsByBudget(budgetId)
    }

    suspend fun getPlannedObligationsByBudgetOnce(budgetId: Int): List<PlannedObligationEntity> {
        return budgetDao.getPlannedObligationsByBudgetOnce(budgetId)
    }

    suspend fun insertPlannedObligation(obligation: PlannedObligationEntity): Long {
        return budgetDao.insertPlannedObligation(obligation)
    }

    suspend fun updatePlannedObligation(obligation: PlannedObligationEntity) {
        budgetDao.updatePlannedObligation(obligation)
    }

    suspend fun deletePlannedObligation(id: Int) = budgetDao.deletePlannedObligation(id)

    suspend fun getPlannedObligationById(id: Int): PlannedObligationEntity? {
        return budgetDao.getPlannedObligationById(id)
    }

    suspend fun insertAuditAction(action: AuditActionEntity): Long = budgetDao.insertAuditAction(action)

    suspend fun getActiveAuditActions(limit: Int = 50): List<AuditActionEntity> {
        return budgetDao.getActiveAuditActions(limit)
    }

    suspend fun markAuditReverted(id: Long) {
        budgetDao.markAuditReverted(id, System.currentTimeMillis())
    }

    suspend fun getAuditActionById(id: Long): AuditActionEntity? = budgetDao.getAuditActionById(id)

    suspend fun restoreCategory(categoryId: Int) = budgetDao.restoreCategory(categoryId)
}
