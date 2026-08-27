package ru.mybudget.app

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.mybudget.app.data.BudgetDatabase
import ru.mybudget.app.data.BudgetProfileEntity
import ru.mybudget.app.data.BudgetRepository
import ru.mybudget.app.setup.ActiveBudgetPreferences
import ru.mybudget.app.setup.ActivePropertyPreferences
import ru.mybudget.app.utilities.UtilityPropertyCopyHelper

class BudgetManager private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val database = BudgetDatabase.getInstance(appContext)
    val repository = BudgetRepository(database.budgetDao())
    val utilityDao = database.utilityDao()

    private var categoriesCache: MutableList<BudgetCategory> = mutableListOf()
    @Volatile
    private var isDataLoaded = false

    init {
        loadInitialData()
    }

    fun clearAllData() {
        coroutineScope.launch {
            repository.deleteAllTransactions()
            repository.deleteAllCategories()
            categoriesCache.clear()
            isDataLoaded = false
            loadCategoriesFromDatabase(persistParentFixes = false)
        }
    }

    private fun loadInitialData() {
        coroutineScope.launch {
            runCatching {
                repository.ensureDefaultBudgetProfile()
                ensureUtilityPropertyReady()
                loadCategoriesFromDatabase(persistParentFixes = true)
            }
        }
    }

    private suspend fun ensureUtilityPropertyReady() {
        val propertyId = UtilityPropertyCopyHelper.ensureDefaultProperty(utilityDao)
        val activeId = ActivePropertyPreferences.getActivePropertyId(appContext)
        if (utilityDao.getPropertyById(activeId) == null) {
            ActivePropertyPreferences.setActivePropertyId(appContext, propertyId)
        }
    }

    fun getCategories(): List<BudgetCategory> {
        return if (isDataLoaded) categoriesCache.toList() else emptyList()
    }

    suspend fun reloadCategoriesFromDatabase() {
        loadCategoriesFromDatabase(persistParentFixes = true)
    }

    suspend fun getCategoriesAsync(forceReload: Boolean = false): List<BudgetCategory> {
        return withContext(Dispatchers.IO) {
            if (forceReload || !isDataLoaded) {
                loadCategoriesFromDatabase(persistParentFixes = false)
            }
            categoriesCache.toList()
        }
    }

    private suspend fun loadCategoriesFromDatabase(persistParentFixes: Boolean) {
        repository.ensureDefaultBudgetProfile()
        val fromDb = repository.getAllCategories().first()
        categoriesCache = fromDb.toMutableList()
        isDataLoaded = true
        if (persistParentFixes) {
            normalizeParentBalances(categoriesCache)
        }
    }

    fun getActiveBudgetId(): Int = ActiveBudgetPreferences.getActiveBudgetId(appContext)

    fun setActiveBudgetId(budgetId: Int) {
        ActiveBudgetPreferences.setActiveBudgetId(appContext, budgetId)
    }

    suspend fun getBudgetProfilesAsync(): List<BudgetProfileEntity> {
        return withContext(Dispatchers.IO) {
            repository.ensureDefaultBudgetProfile()
            repository.getAllBudgetProfilesOnce()
        }
    }

    suspend fun createBudgetProfile(name: String): Int {
        return withContext(Dispatchers.IO) {
            repository.insertBudgetProfile(name)
        }
    }

    suspend fun renameBudgetProfile(id: Int, name: String) {
        withContext(Dispatchers.IO) {
            val profile = repository.getBudgetProfileById(id) ?: return@withContext
            repository.updateBudgetProfile(profile.copy(name = name))
        }
    }

    suspend fun deleteBudgetProfile(id: Int): Boolean {
        if (id == ActiveBudgetPreferences.DEFAULT_BUDGET_ID) return false
        return withContext(Dispatchers.IO) {
            val profiles = repository.getAllBudgetProfilesOnce()
            if (profiles.size <= 1) return@withContext false
            repository.deactivateBudgetProfile(id)
            if (getActiveBudgetId() == id) {
                setActiveBudgetId(ActiveBudgetPreferences.DEFAULT_BUDGET_ID)
            }
            true
        }
    }

    fun getCategoryIdsForBudget(budgetId: Int = getActiveBudgetId()): Set<Int> {
        return getCategoriesForBudget(budgetId).map { it.id }.toSet()
    }

    fun getCategoriesForBudget(budgetId: Int = getActiveBudgetId()): List<BudgetCategory> {
        return getCategories().filter { it.budgetId == budgetId && it.isActive }
    }

    suspend fun getCategoriesForActiveBudgetAsync(): List<BudgetCategory> {
        getCategoriesAsync()
        return getCategoriesForBudget(getActiveBudgetId())
    }

    fun getRootCategories(budgetId: Int = getActiveBudgetId()): List<BudgetCategory> {
        return getCategories().filter { it.parentId == 0 && it.isActive && it.budgetId == budgetId }
    }

    fun getSubCategories(parentId: Int): List<BudgetCategory> {
        return getCategories().filter { it.parentId == parentId && it.isActive }
    }

    suspend fun getCategoryById(id: Int): BudgetCategory? {
        return withContext(Dispatchers.IO) {
            categoriesCache.firstOrNull { it.id == id } ?: repository.getCategoryById(id)
        }
    }

    suspend fun addCategory(
        name: String,
        parentId: Int = 0,
        colorHex: String = "",
        budgetId: Int? = null,
    ): BudgetCategory {
        return withContext(Dispatchers.IO) {
            val resolvedBudgetId = when {
                budgetId != null -> budgetId
                parentId != 0 -> categoriesCache.firstOrNull { it.id == parentId }?.budgetId ?: getActiveBudgetId()
                else -> getActiveBudgetId()
            }
            val newId = maxOf(repository.getMaxCategoryId(), categoriesCache.maxOfOrNull { it.id } ?: 0) + 1
            val position = repository.getMaxPositionForParent(parentId) + 1
            val newCategory = BudgetCategory(
                id = newId,
                name = name,
                parentId = parentId,
                budgetId = resolvedBudgetId,
                colorHex = colorHex,
                position = position,
            )
            repository.insertCategory(newCategory)
            loadCategoriesFromDatabase(persistParentFixes = false)
            categoriesCache.first { it.id == newId }
        }
    }

    suspend fun updateCategory(
        categoryId: Int,
        newName: String,
        newPlannedAmount: Double,
        newColorHex: String? = null,
    ) {
        withContext(Dispatchers.IO) {
            val category = categoriesCache.firstOrNull { it.id == categoryId } ?: return@withContext
            category.name = newName
            category.plannedAmount = newPlannedAmount
            if (newColorHex != null) category.colorHex = newColorHex
            repository.updateCategory(category)
            loadCategoriesFromDatabase(persistParentFixes = false)
        }
    }

    suspend fun recordTransaction(
        categoryId: Int,
        amount: Double,
        type: String,
        description: String,
    ) {
        withContext(Dispatchers.IO) {
            repository.recordTransaction(categoryId, amount, type, description)
            loadCategoriesFromDatabase(persistParentFixes = false)
        }
    }

    suspend fun applyTransactionGroup(
        items: List<Pair<Int, Double>>,
        type: String,
        description: String,
    ) {
        withContext(Dispatchers.IO) {
            val groupId = java.util.UUID.randomUUID().toString()
            repository.applyTransactionGroup(items, type, description, groupId)
            loadCategoriesFromDatabase(persistParentFixes = false)
        }
    }

    fun updateDefaultIncomeAmount(categoryId: Int, amount: Double) {
        val category = categoriesCache.firstOrNull { it.id == categoryId } ?: return
        category.defaultIncomeAmount = amount
        coroutineScope.launch { repository.updateDefaultIncomeAmount(categoryId, amount) }
    }

    fun updateDefaultPlannedAmount(categoryId: Int, amount: Double) {
        val category = categoriesCache.firstOrNull { it.id == categoryId } ?: return
        category.defaultPlannedAmount = amount
        coroutineScope.launch { repository.updateDefaultPlannedAmount(categoryId, amount) }
    }

    fun deleteCategory(categoryId: Int) {
        coroutineScope.launch { removeCategory(categoryId) }
    }

    suspend fun removeCategory(categoryId: Int): Boolean {
        if (hasSubcategories(categoryId)) return false
        return withContext(Dispatchers.IO) {
            repository.deleteCategory(categoryId)
            loadCategoriesFromDatabase(persistParentFixes = true)
            true
        }
    }

    suspend fun deleteSubcategoryWithTransfer(
        categoryId: Int,
        targetCategoryId: Int? = null,
        recordAudit: Boolean = true,
    ): Boolean = withContext(Dispatchers.IO) {
        val category = categoriesCache.firstOrNull { it.id == categoryId } ?: return@withContext false
        if (category.parentId == 0) return@withContext false
        val balance = category.currentBalance
        if (balance != 0.0) {
            val targetId = targetCategoryId ?: return@withContext false
            if (targetId == categoryId) return@withContext false
            repository.transferBetweenLeafCategories(categoryId, targetId, balance)
        }
        repository.deleteCategory(categoryId)
        if (recordAudit) {
            val targetName = targetCategoryId?.let { id ->
                categoriesCache.firstOrNull { it.id == id }?.name
                    ?: repository.getCategoryById(id)?.name
            }
            AuditLogHelper.recordCategoryDeleted(
                repository,
                AuditLogHelper.CategoryDeletePayload(
                    categoryId = category.id,
                    categoryName = category.name,
                    parentId = category.parentId,
                    budgetId = category.budgetId,
                    balance = balance,
                    targetCategoryId = targetCategoryId,
                    targetCategoryName = targetName,
                ),
            )
        }
        reloadCategoriesFromDatabase()
        true
    }

    fun getTotalBalance(budgetId: Int = getActiveBudgetId()): Double {
        return getRootCategories(budgetId).sumOf { getCategoryBalanceWithSubcategories(it.id) }
    }

    suspend fun getTotalBalanceAll(): Double {
        getCategoriesAsync()
        return getCategories()
            .filter { it.parentId == 0 && it.isActive }
            .sumOf { getCategoryBalanceWithSubcategories(it.id) }
    }

    suspend fun getBudgetProfileTotals(): List<Pair<BudgetProfileEntity, Double>> {
        val profiles = getBudgetProfilesAsync()
        getCategoriesAsync()
        return profiles.map { profile -> profile to getTotalBalance(profile.id) }
    }

    fun getCategoryBalanceWithSubcategories(categoryId: Int): Double {
        val category = categoriesCache.firstOrNull { it.id == categoryId } ?: return 0.0
        return category.currentBalance
    }

    fun getParentRemainingBalance(categoryId: Int): Double {
        val parent = categoriesCache.firstOrNull { it.id == categoryId } ?: return 0.0
        val childrenSum = getSubCategories(categoryId).sumOf { it.currentBalance }
        return (parent.currentBalance - childrenSum).coerceAtLeast(0.0)
    }

    fun getTotalBalanceIncludingParent(categoryId: Int): Double {
        return getCategoryBalanceWithSubcategories(categoryId)
    }

    fun canEditBalance(categoryId: Int): Boolean = !hasSubcategories(categoryId)

    fun hasSubcategories(categoryId: Int): Boolean = getSubCategories(categoryId).isNotEmpty()

    fun isParentCategory(categoryId: Int): Boolean = hasSubcategories(categoryId)

    fun getCategoriesForExpenses(): List<BudgetCategory> {
        val budgetId = getActiveBudgetId()
        return getCategoriesForBudget(budgetId).filter { !hasSubcategories(it.id) }
    }

    suspend fun getCategoriesForExpensesAsync(): List<BudgetCategory> {
        getCategoriesAsync()
        return getCategoriesForExpenses()
    }

    suspend fun transferSubcategoryBalance(fromId: Int, toId: Int, amount: Double): Boolean {
        if (amount <= 0.0 || fromId == toId) return false
        return withContext(Dispatchers.IO) {
            getCategoriesAsync()
            val from = categoriesCache.firstOrNull { it.id == fromId } ?: return@withContext false
            val to = categoriesCache.firstOrNull { it.id == toId } ?: return@withContext false
            if (from.parentId == 0 || to.parentId == 0) return@withContext false
            if (hasSubcategories(fromId) || hasSubcategories(toId)) return@withContext false
            if (from.currentBalance + 1.0E-9 < amount) return@withContext false
            repository.transferBetweenLeafCategories(fromId, toId, amount)
            loadCategoriesFromDatabase(persistParentFixes = false)
            true
        }
    }

    suspend fun distributeParentRemainder(
        parentId: Int,
        items: List<Pair<Int, Double>>,
        description: String,
    ): Boolean {
        return withContext(Dispatchers.IO) {
            loadCategoriesFromDatabase(persistParentFixes = false)
            val leftover = getParentRemainingBalance(parentId)
            val total = items.sumOf { it.second }
            if (items.isEmpty() || total <= 0.0 || total > leftover + 0.01) return@withContext false
            applyTransactionGroup(items, "income", description)
            recordTransaction(parentId, total, "expense", description)
            true
        }
    }

    private suspend fun normalizeParentBalances(source: List<BudgetCategory>) {
        val parents = source.filter { it.parentId == 0 }
        for (parent in parents) {
            val children = source.filter { it.parentId == parent.id && it.isActive }
            if (children.isEmpty()) continue
            val total = children.sumOf { it.currentBalance }
            if (parent.currentBalance + 0.005 < total) {
                parent.currentBalance = total
                repository.updateCategory(parent)
            }
        }
    }

    companion object {
        @Volatile
        private var instance: BudgetManager? = null

        fun getInstance(context: Context): BudgetManager {
            return instance ?: synchronized(this) {
                instance ?: BudgetManager(context).also { instance = it }
            }
        }
    }
}
