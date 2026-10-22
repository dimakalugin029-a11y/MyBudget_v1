package ru.mybudget.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
abstract class BudgetDao {
    @Query("SELECT * FROM balance_ledger_state WHERE id=1")
    abstract suspend fun getLedgerState(): BalanceLedgerStateEntity?
    @Upsert
    abstract suspend fun setLedgerState(state: BalanceLedgerStateEntity)
    @Query("SELECT * FROM balance_movements WHERE (:budgetId IS NULL OR budgetId=:budgetId) AND createdAt < :until ORDER BY createdAt,id")
    abstract suspend fun getBalanceMovements(budgetId: Int?, until: Long): List<BalanceMovementEntity>
    @Query("SELECT * FROM balance_movements ORDER BY id")
    abstract suspend fun getAllBalanceMovements(): List<BalanceMovementEntity>
    @Query("DELETE FROM balance_movements")
    abstract suspend fun deleteBalanceMovements()
    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertBalanceMovements(rows: List<BalanceMovementEntity>)
    @Query("INSERT INTO balance_movements (categoryId,budgetId,delta,budgetDelta,createdAt,kind,operationId) SELECT id,budgetId,currentBalance,CASE WHEN parentId=0 AND isActive=1 THEN currentBalance ELSE 0 END,:at,'opening','baseline' FROM categories")
    abstract suspend fun insertLedgerOpening(at: Long)
    @Transaction
    open suspend fun resetBalanceLedger() {
        deleteBalanceMovements()
        val at = System.currentTimeMillis() / 1000 * 1000
        setLedgerState(BalanceLedgerStateEntity(startedAt = at))
        insertLedgerOpening(at)
    }

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun claimMoneyCommand(command: MoneyCommandEntity): Long
    @Query("SELECT * FROM obligation_payments WHERE transactionId = :transactionId")
    abstract suspend fun getPaymentsForTransaction(transactionId: Int): List<ObligationPaymentEntity>

    @Query("DELETE FROM obligation_payments WHERE transactionId = :transactionId")
    abstract suspend fun deletePaymentsForTransaction(transactionId: Int)

    @Query("DELETE FROM obligation_payments WHERE id = :id")
    abstract suspend fun deleteObligationPaymentById(id: Int)

    @Query("SELECT * FROM obligation_payments WHERE id = :id")
    abstract suspend fun getObligationPaymentById(id: Int): ObligationPaymentEntity?

    @Transaction
    open suspend fun reopenObligationPayment(id: Int) {
        val payment = getObligationPaymentById(id) ?: return
        val transaction = payment.transactionId?.let { getTransactionById(it) }
        if (transaction != null) { revertAndDeleteTransaction(transaction); return }
        val obligation = getPlannedObligationById(payment.obligationId)
        if (obligation != null) {
            val due = payment.dueDate ?: ru.mybudget.app.PlannedObligationHelper.dueLocalDate(
                java.time.YearMonth.of(payment.periodYear, payment.periodMonth), obligation.dueDay).toString()
            getLinkedReminders(payment.obligationId).filter { it.dueDate > due }.forEach { updateReminderDueDate(it.id, due) }
            getLinkedRecurring(payment.obligationId).filter { it.nextDueDate > due }.forEach { updateRecurringNextDate(it.id, due) }
        }
        deleteObligationPaymentById(id)
    }

    @Query("UPDATE obligation_payments SET amount = :amount WHERE transactionId = :transactionId")
    abstract suspend fun updatePaymentAmount(transactionId: Int, amount: Double)

    @Query("UPDATE vehicle_service_logs SET paymentMethod = 'unpaid', paidTransactionId = NULL WHERE paidTransactionId = :id")
    abstract suspend fun unlinkServicePayment(id: Int)
    @Query("UPDATE vehicle_insurances SET paymentMethod = 'unpaid', paidTransactionId = NULL WHERE paidTransactionId = :id")
    abstract suspend fun unlinkInsurancePayment(id: Int)
    @Query("UPDATE vehicle_fuel_logs SET paymentMethod = 'unpaid', paidTransactionId = NULL WHERE paidTransactionId = :id")
    abstract suspend fun unlinkFuelPayment(id: Int)
    @Query("UPDATE vehicle_repairs SET paymentMethod = 'unpaid', paidTransactionId = NULL WHERE paidTransactionId = :id")
    abstract suspend fun unlinkRepairPayment(id: Int)
    @Query("UPDATE vehicle_service_logs SET amount = :amount, categoryId = :categoryId WHERE paidTransactionId = :id")
    abstract suspend fun correctServicePayment(id: Int, amount: Double, categoryId: Int)
    @Query("UPDATE vehicle_insurances SET amount = :amount, categoryId = :categoryId WHERE paidTransactionId = :id")
    abstract suspend fun correctInsurancePayment(id: Int, amount: Double, categoryId: Int)
    @Query("UPDATE vehicle_fuel_logs SET amount = :amount, categoryId = :categoryId WHERE paidTransactionId = :id")
    abstract suspend fun correctFuelPayment(id: Int, amount: Double, categoryId: Int)
    @Query("UPDATE vehicle_repairs SET amount = :amount, categoryId = :categoryId WHERE paidTransactionId = :id")
    abstract suspend fun correctRepairPayment(id: Int, amount: Double, categoryId: Int)

    private suspend fun undoPaymentLinks(transactionId: Int) {
        for (payment in getPaymentsForTransaction(transactionId)) {
            val obligation = getPlannedObligationById(payment.obligationId) ?: continue
            val due = payment.dueDate ?: ru.mybudget.app.PlannedObligationHelper.dueLocalDate(
                java.time.YearMonth.of(payment.periodYear, payment.periodMonth), obligation.dueDay).toString()
            getLinkedReminders(payment.obligationId).filter { it.dueDate > due }.forEach { updateReminderDueDate(it.id, due) }
            getLinkedRecurring(payment.obligationId).filter { it.nextDueDate > due }.forEach { updateRecurringNextDate(it.id, due) }
        }
        deletePaymentsForTransaction(transactionId)
        unlinkServicePayment(transactionId); unlinkInsurancePayment(transactionId)
        unlinkFuelPayment(transactionId); unlinkRepairPayment(transactionId)
    }

    @Query("SELECT * FROM planned_income_sources WHERE id = :id")
    abstract suspend fun getIncomeSourceById(id: Int): PlannedIncomeSourceEntity?

    @Query("SELECT t.* FROM transactions t INNER JOIN categories c ON c.id = t.categoryId WHERE c.budgetId = :budgetId AND t.type = 'income' AND t.plannedIncomeSourceId IS NOT NULL")
    abstract suspend fun getIncomePlanTransactions(budgetId: Int): List<TransactionEntity>

    @Query("SELECT * FROM payment_reminders WHERE obligationId = :obligationId")
    abstract suspend fun getLinkedReminders(obligationId: Int): List<PaymentReminderEntity>

    @Query("SELECT * FROM recurring_transactions WHERE obligationId = :obligationId")
    abstract suspend fun getLinkedRecurring(obligationId: Int): List<RecurringTransactionEntity>

    @Query("SELECT * FROM payment_reminders WHERE isActive = 1 AND dueDate <= :through ORDER BY dueDate")
    abstract suspend fun getOpenRemindersThrough(through: String): List<PaymentReminderEntity>

    @Query("SELECT * FROM recurring_transactions WHERE isActive = 1 AND nextDueDate <= :through ORDER BY nextDueDate")
    abstract suspend fun getOpenRecurringThrough(through: String): List<RecurringTransactionEntity>
    @Query("SELECT * FROM monthly_income_plans")
    abstract suspend fun getAllMonthlyIncomePlansForExport(): List<MonthlyIncomePlanEntity>

    @Query("DELETE FROM monthly_income_plans")
    abstract suspend fun deleteAllMonthlyIncomePlans()

    @Query("SELECT * FROM forecast_snapshots")
    abstract suspend fun getAllForecastSnapshotsForExport(): List<ForecastSnapshotEntity>

    @Query("DELETE FROM forecast_snapshots")
    abstract suspend fun deleteAllForecastSnapshots()

    @Transaction
    open suspend fun correctTransaction(
        transactionId: Int,
        newCategoryId: Int,
        newAmount: Double,
        newType: String,
        newDescription: String,
        newParticipantLabel: String,
    ) {
        require(newAmount.isFinite() && newAmount > 0.0) { "Invalid transaction amount" }
        require(newType == "income" || newType == "expense") { "Invalid transaction type" }
        val current = requireNotNull(getTransactionById(transactionId)) { "Transaction no longer exists" }
        val oldCategory = requireNotNull(getCategoryByIdAny(current.categoryId)) { "Original category is unavailable" }
        val newCategory = requireNotNull(getCategoryById(newCategoryId)) { "New category is unavailable" }
        val rounded = ru.mybudget.app.MoneyFormat.roundMoney(newAmount)
        require(rounded.isFinite() && rounded > 0.0) { "Invalid rounded amount" }
        val revert = if (current.type == "income") -current.amount else current.amount
        val operation = "correct-$transactionId-${java.util.UUID.randomUUID()}"
        applyBalanceDelta(current.categoryId, revert, "correction", operation)
        applyBalanceDelta(newCategoryId, if (newType == "income") rounded else -rounded, "correction", operation)
        updateTransaction(current.copy(
            categoryId = newCategoryId,
            amount = rounded,
            type = newType,
            description = newDescription,
            participantLabel = newParticipantLabel.trim(),
            plannedIncomeSourceId = current.plannedIncomeSourceId.takeIf { newType == "income" && oldCategory.budgetId == newCategory.budgetId },
            plannedIncomeMonth = current.plannedIncomeMonth.takeIf { newType == "income" && oldCategory.budgetId == newCategory.budgetId },
        ))
        if (newType != "expense") undoPaymentLinks(transactionId)
        else {
            updatePaymentAmount(transactionId, rounded)
            correctServicePayment(transactionId, rounded, newCategoryId)
            correctInsurancePayment(transactionId, rounded, newCategoryId)
            correctFuelPayment(transactionId, rounded, newCategoryId)
            correctRepairPayment(transactionId, rounded, newCategoryId)
        }
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertBudgetProfile(profile: BudgetProfileEntity): Long

    @Upsert
    abstract suspend fun insertCategory(category: BudgetCategoryEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertAuditAction(action: AuditActionEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertMonthlyPlan(plan: MonthlyCategoryPlanEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertMonthlyIncomePlan(plan: MonthlyIncomePlanEntity)

    @Query("SELECT * FROM monthly_income_plans WHERE budgetId = :budgetId")
    abstract suspend fun getMonthlyIncomePlansForBudget(budgetId: Int): List<MonthlyIncomePlanEntity>

    @Query(
        """
        SELECT * FROM monthly_income_plans
        WHERE budgetId = :budgetId AND year = :year AND month = :month
    """,
    )
    abstract suspend fun getMonthlyIncomePlansForMonth(
        budgetId: Int,
        year: Int,
        month: Int,
    ): List<MonthlyIncomePlanEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertTransaction(transaction: TransactionEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertReminder(reminder: PaymentReminderEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertSavingsGoal(goal: SavingsGoalEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertRecurring(r: RecurringTransactionEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertPlannedObligation(obligation: PlannedObligationEntity): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertObligationPayment(payment: ObligationPaymentEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertBalanceSnapshot(snapshot: BalanceSnapshotEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertPlannedIncomeSource(source: PlannedIncomeSourceEntity): Long

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

    @Update
    abstract suspend fun updatePlannedIncomeSource(source: PlannedIncomeSourceEntity)

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

    @Query("SELECT * FROM audit_actions ORDER BY createdAt ASC")
    abstract suspend fun getAllAuditActionsForExport(): List<AuditActionEntity>

    @Query("SELECT * FROM monthly_category_plans")
    abstract suspend fun getAllMonthlyPlansForExport(): List<MonthlyCategoryPlanEntity>

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

    @Query("SELECT * FROM monthly_category_plans WHERE budgetId = :budgetId")
    abstract suspend fun getMonthlyPlansForBudget(budgetId: Int): List<MonthlyCategoryPlanEntity>

    @Query("SELECT * FROM transactions WHERE date >= :fromMs AND date < :toMs ORDER BY date ASC")
    abstract suspend fun getTransactionsInRange(fromMs: Long, toMs: Long): List<TransactionEntity>

    @Query("SELECT * FROM savings_goals ORDER BY name")
    abstract suspend fun getAllSavingsGoalsList(): List<SavingsGoalEntity>

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

    @Query("SELECT * FROM transactions")
    abstract suspend fun getAllTransactionsOnce(): List<TransactionEntity>

    @Query("SELECT * FROM transactions WHERE type = 'expense' ORDER BY date DESC LIMIT :limit")
    abstract suspend fun getRecentExpenseTransactions(limit: Int): List<TransactionEntity>

    @Query("""
        SELECT
            CASE WHEN MIN(t.description) = MAX(t.description) THEN MIN(t.description) ELSE '' END AS description,
            MIN(c.name) AS categoryName,
            COUNT(DISTINCT c.id) AS categoryCount,
            SUM(CASE WHEN t.type = 'income' THEN t.amount ELSE -t.amount END) AS netAmount,
            MAX(t.date) AS date,
            COUNT(DISTINCT t.type) > 1 AS mixedTypes,
            CASE WHEN t.groupId IS NULL OR TRIM(t.groupId) = '' THEN 'tx:' || t.id ELSE 'group:' || t.groupId END AS entryKey
        FROM transactions t
        INNER JOIN categories c ON c.id = t.categoryId
        WHERE c.budgetId = :budgetId
        GROUP BY entryKey
        ORDER BY date DESC, MAX(t.id) DESC LIMIT :limit
    """)
    abstract suspend fun getRecentDashboardTransactions(budgetId: Int, limit: Int): List<RecentDashboardTransaction>

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

    @Query("SELECT * FROM payment_reminders WHERE obligationId = :obligationId LIMIT 1")
    abstract suspend fun getReminderByObligationId(obligationId: Int): PaymentReminderEntity?

    @Query("DELETE FROM payment_reminders WHERE obligationId = :obligationId")
    abstract suspend fun deleteRemindersByObligationId(obligationId: Int)

    @Query("SELECT * FROM recurring_transactions WHERE obligationId = :obligationId LIMIT 1")
    abstract suspend fun getRecurringByObligationId(obligationId: Int): RecurringTransactionEntity?

    @Query("DELETE FROM recurring_transactions WHERE obligationId = :obligationId")
    abstract suspend fun deleteRecurringByObligationId(obligationId: Int)

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

    @Query("SELECT * FROM savings_goals ORDER BY name")
    abstract fun getAllSavingsGoalsIncludingArchived(): Flow<List<SavingsGoalEntity>>

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
        SELECT op.* FROM obligation_payments op
        INNER JOIN planned_obligations po ON po.id = op.obligationId
        WHERE po.budgetId = :budgetId
        """,
    )
    abstract suspend fun getObligationPaymentsByBudget(budgetId: Int): List<ObligationPaymentEntity>

    @Query("SELECT * FROM obligation_payments ORDER BY id")
    abstract suspend fun getAllObligationPaymentsForExport(): List<ObligationPaymentEntity>

    @Query(
        """
        SELECT EXISTS(
            SELECT 1 FROM obligation_payments
            WHERE obligationId = :obligationId AND periodYear = :year AND periodMonth = :month
        )
        """,
    )
    abstract suspend fun isObligationPeriodPaid(obligationId: Int, year: Int, month: Int): Boolean

    @Query("DELETE FROM obligation_payments WHERE obligationId = :obligationId")
    abstract suspend fun deleteObligationPaymentsByObligation(obligationId: Int)

    @Query("DELETE FROM obligation_payments")
    abstract suspend fun deleteAllObligationPayments()

    @Query("SELECT * FROM planned_income_sources WHERE isActive = 1 AND budgetId = :budgetId ORDER BY sortOrder, id")
    abstract suspend fun getPlannedIncomeSourcesByBudgetOnce(budgetId: Int): List<PlannedIncomeSourceEntity>

    @Query("SELECT * FROM planned_income_sources ORDER BY id")
    abstract suspend fun getAllPlannedIncomeSourcesForExport(): List<PlannedIncomeSourceEntity>

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

    @Query(
        """
        SELECT * FROM transactions
        WHERE LOWER(description) LIKE '%' || :q || '%'
        ORDER BY date DESC LIMIT :limit
        """,
    )
    abstract suspend fun searchTransactions(q: String, limit: Int): List<TransactionEntity>

    @Query(
        """
        SELECT * FROM categories
        WHERE budgetId = :budgetId AND isActive = 1 AND LOWER(name) LIKE '%' || :q || '%'
        ORDER BY name LIMIT :limit
        """,
    )
    abstract suspend fun searchCategories(
        budgetId: Int,
        q: String,
        limit: Int,
    ): List<BudgetCategoryEntity>

    @Query(
        """
        SELECT * FROM planned_obligations
        WHERE budgetId = :budgetId AND LOWER(name) LIKE '%' || :q || '%'
        ORDER BY name LIMIT :limit
        """,
    )
    abstract suspend fun searchObligations(
        budgetId: Int,
        q: String,
        limit: Int,
    ): List<PlannedObligationEntity>

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

    @Query("UPDATE planned_income_sources SET isActive = 0 WHERE id = :id")
    abstract suspend fun deletePlannedIncomeSource(id: Int)

    @Query("DELETE FROM planned_income_sources")
    abstract suspend fun deleteAllPlannedIncomeSources()

    @Query("DELETE FROM monthly_category_plans")
    abstract suspend fun deleteAllMonthlyPlans()

    @Query("DELETE FROM audit_actions")
    abstract suspend fun deleteAllAuditActions()

    @Query(
        """
        SELECT COALESCE(SUM(currentBalance), 0) FROM categories
        WHERE budgetId = :budgetId AND parentId = 0 AND isActive = 1
        """,
    )
    abstract suspend fun getRootBalanceSumForBudget(budgetId: Int): Double

    @Query(
        """
        SELECT * FROM balance_snapshots
        WHERE budgetId = :budgetId AND dayKey >= :fromDay AND dayKey <= :toDay
        ORDER BY dayKey
        """,
    )
    abstract suspend fun getBalanceSnapshotsForBudget(
        budgetId: Int,
        fromDay: Long,
        toDay: Long,
    ): List<BalanceSnapshotEntity>

    @Query(
        """
        SELECT * FROM balance_snapshots
        WHERE dayKey >= :fromDay AND dayKey <= :toDay
        ORDER BY dayKey, budgetId
        """,
    )
    abstract suspend fun getAllBalanceSnapshotsInRange(fromDay: Long, toDay: Long): List<BalanceSnapshotEntity>

    @Query("SELECT * FROM balance_snapshots ORDER BY dayKey, budgetId")
    abstract suspend fun getAllBalanceSnapshotsForExport(): List<BalanceSnapshotEntity>

    @Query("DELETE FROM balance_snapshots")
    abstract suspend fun deleteAllBalanceSnapshots()

    @Transaction
    open suspend fun recordBalanceSnapshotForBudget(budgetId: Int) {
        val total = getRootBalanceSumForBudget(budgetId)
        val dayKey = java.time.LocalDate.now().toEpochDay()
        upsertBalanceSnapshot(
            BalanceSnapshotEntity(
                budgetId = budgetId,
                dayKey = dayKey,
                totalBalance = total,
            ),
        )
    }

    @Transaction
    open suspend fun recordBalanceSnapshotForCategory(categoryId: Int) {
        val category = getCategoryById(categoryId)
            ?: getCategoryByIdAny(categoryId)
            ?: return
        recordBalanceSnapshotForBudget(category.budgetId)
    }

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
    open suspend fun applyBalanceDelta(categoryId: Int, amount: Double, kind: String = "adjustment", operationId: String = "") {
        require(amount.isFinite())
        val previous = getLedgerState()
        if (previous == null) resetBalanceLedger()
        val state = requireNotNull(getLedgerState())
        setLedgerState(state.copy(kind=kind, operationId=operationId))
        val category = getCategoryByIdAny(categoryId) ?: error("Статья операции отсутствует")
        updateCategory(category.copy(currentBalance = category.currentBalance + amount))
        if (category.parentId != 0) {
            val parent = requireNotNull(getCategoryByIdAny(category.parentId)) { "Родительская статья отсутствует" }
            updateCategory(parent.copy(currentBalance = parent.currentBalance + amount))
        }
        recordBalanceSnapshotForCategory(categoryId)
        setLedgerState(state)
    }

    @Transaction
    open suspend fun recordTransaction(
        categoryId: Int,
        amount: Double,
        type: String,
        description: String,
        groupId: String? = null,
        date: Long = System.currentTimeMillis(),
        participantLabel: String = "",
    ) {
        require(amount.isFinite() && amount > 0.0 && type in listOf("income", "expense")) { "Некорректная операция" }
        requireNotNull(getCategoryById(categoryId)) { "Статья недоступна" }
        val insertedTransactionId = insertTransaction(
            TransactionEntity(
                categoryId = categoryId,
                amount = amount,
                type = type,
                description = description,
                date = date,
                groupId = groupId,
                participantLabel = participantLabel,
            ),
        )
        val delta = if (type == "income") amount else -amount
        applyBalanceDelta(categoryId, delta, type, groupId ?: "tx-$insertedTransactionId")
    }

    @Transaction
    open suspend fun recordSingleWithRequest(categoryId: Int, amount: Double, type: String,
        description: String, participantLabel: String, requestId: String) {
        if (getTransactionsByGroupId(requestId).isNotEmpty() || claimMoneyCommand(MoneyCommandEntity(requestId)) == -1L) return
        recordTransaction(categoryId, amount, type, description, groupId = requestId, participantLabel = participantLabel)
    }

    @Transaction
    open suspend fun revertAndDeleteTransaction(transaction: TransactionEntity) {
        val current = getTransactionById(transaction.id) ?: return
        val delta = if (current.type == "income") -current.amount else current.amount
        applyBalanceDelta(current.categoryId, delta, "reversal", "cancel-${current.id}")
        undoPaymentLinks(current.id)
        deleteTransaction(current.id)
    }

    @Transaction
    open suspend fun cancelTransactionGroup(groupId: String) {
        val txs = getTransactionsByGroupId(groupId)
        for (transaction in txs) {
            revertAndDeleteTransaction(transaction)
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
        if (getTransactionsByGroupId(groupId).isNotEmpty()) return
        require(categoryIds.size == amounts.size && categoryIds.isNotEmpty())
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
    open suspend fun transferBetweenLeafCategories(fromId: Int, toId: Int, amount: Double, requestId: String? = null, operationId: String? = null) {
        if (requestId != null && claimMoneyCommand(MoneyCommandEntity(requestId)) == -1L) return
        require(amount.isFinite() && amount > 0.0 && fromId != toId)
        val from = requireNotNull(getCategoryById(fromId))
        requireNotNull(getCategoryById(toId))
        require(from.currentBalance + 0.001 >= amount) { "Недостаточно средств" }
        val operation = operationId ?: requestId ?: java.util.UUID.randomUUID().toString()
        applyBalanceDelta(fromId, -amount, "transfer", operation)
        applyBalanceDelta(toId, amount, "transfer", operation)
    }

    @Transaction
    open suspend fun distributeBalance(fromId: Int, ids: IntArray, amounts: DoubleArray, requestId: String? = null) {
        if (requestId != null && claimMoneyCommand(MoneyCommandEntity(requestId)) == -1L) return
        require(ids.isNotEmpty() && ids.size == amounts.size)
        val from = requireNotNull(getCategoryById(fromId))
        require(amounts.all { it.isFinite() && it > 0.0 } && ids.none { it == fromId })
        require(from.currentBalance + 0.001 >= amounts.sum()) { "Недостаточно средств" }
        val operation = requestId ?: java.util.UUID.randomUUID().toString()
        for (index in ids.indices) transferBetweenLeafCategories(fromId, ids[index], amounts[index], operationId=operation)
    }

    @Transaction
    open suspend fun relocateCategoryBalance(fromId: Int, toId: Int, amount: Double) {
        require(amount.isFinite() && fromId != toId)
        requireNotNull(getCategoryById(fromId)); requireNotNull(getCategoryById(toId))
        val operation = java.util.UUID.randomUUID().toString()
        applyBalanceDelta(fromId, -amount, "transfer", operation)
        applyBalanceDelta(toId, amount, "transfer", operation)
    }

    @Transaction
    open suspend fun moveSubcategoryToCategory(categoryId: Int, newParentId: Int) {
        val category = getCategoryById(categoryId) ?: return
        val oldParentId = category.parentId
        if (oldParentId == newParentId) return
        val balance = category.currentBalance
        if (oldParentId != 0) {
            applyBalanceDelta(oldParentId, -balance, "reclassify", "move-$categoryId")
        }
        updateCategory(category.copy(parentId = newParentId))
        if (newParentId != 0) {
            applyBalanceDelta(newParentId, balance, "reclassify", "move-$categoryId")
        }
        recordBalanceSnapshotForCategory(categoryId)
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertForecastSnapshot(snapshot: ForecastSnapshotEntity)

    @Query(
        """
        SELECT * FROM forecast_snapshots
        WHERE budgetId = :budgetId
        ORDER BY year DESC, month DESC, scenario ASC
        """,
    )
    abstract suspend fun getForecastSnapshots(budgetId: Int): List<ForecastSnapshotEntity>

    @Query(
        """
        SELECT * FROM forecast_snapshots
        WHERE budgetId = :budgetId AND year = :year AND month = :month
        ORDER BY scenario ASC
        """,
    )
    abstract suspend fun getForecastSnapshotsForMonth(
        budgetId: Int,
        year: Int,
        month: Int,
    ): List<ForecastSnapshotEntity>
}
