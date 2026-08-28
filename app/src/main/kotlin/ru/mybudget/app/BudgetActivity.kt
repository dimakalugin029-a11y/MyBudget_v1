package ru.mybudget.app

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import ru.mybudget.app.data.BudgetDatabase
import ru.mybudget.app.setup.OverspendPreferences

class BudgetActivity : AppCompatActivity() {
    private lateinit var manager: BudgetManager
    private lateinit var categoriesContainer: LinearLayout
    private lateinit var categoriesScrollView: ScrollView
    private lateinit var totalBalanceText: TextView
    private lateinit var activeBudgetNameText: TextView
    private lateinit var safeToSpendText: TextView
    private val expandedParentIds = mutableSetOf<Int>()
    private var listFilter = BudgetPlanHelper.ListFilter.ALL
    private var monthlySpentMap: Map<Int, Double> = emptyMap()
    private var monthlyPlannedMap: Map<Int, Double> = emptyMap()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_budget)
        manager = BudgetManager.getInstance(this)
        ScreenHeaderHelper.setup(this, getString(R.string.budget_screen_title), getString(R.string.main_icon_budget))
        ScreenHeaderHelper.bindAction(
            this,
            android.R.drawable.ic_input_add,
            R.string.budget_add_category_btn,
        ) {
            BudgetDialogs.showAddCategory(this, manager) { reload() }
        }
        ScreenHeaderHelper.bindSecondaryAction(
            this,
            android.R.drawable.ic_popup_sync,
            R.string.budget_refresh_btn,
        ) {
            reload()
            Toast.makeText(this, R.string.budget_refreshed, Toast.LENGTH_SHORT).show()
        }
        findViewById<View>(R.id.budgetHint)?.let {
            ScreenHintHelper.bind(this, it, ScreenHintHelper.Keys.BUDGET, R.string.hint_budget, showHelpLink = false)
        }

        categoriesContainer = findViewById(R.id.categoriesContainer)
        categoriesScrollView = findViewById(R.id.categoriesScrollView)
        totalBalanceText = findViewById(R.id.totalBalanceText)
        activeBudgetNameText = findViewById(R.id.activeBudgetNameText)
        safeToSpendText = findViewById(R.id.safeToSpendText)

        findViewById<View>(R.id.activeBudgetPicker).setOnClickListener {
            BudgetPicker.show(this, onSwitched = { reload() })
        }
        findViewById<View>(R.id.budgetExpandToggle).setOnClickListener { toggleExpandAll() }
        findViewById<View>(R.id.selectionBar).visibility = View.GONE
        setupFilters()
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun setupFilters() {
        val all = findViewById<TextView>(R.id.budgetFilterAll)
        val nonZero = findViewById<TextView>(R.id.budgetFilterNonZero)
        val overspend = findViewById<TextView>(R.id.budgetFilterOverspend)
        fun select(filter: BudgetPlanHelper.ListFilter) {
            listFilter = filter
            all.isSelected = filter == BudgetPlanHelper.ListFilter.ALL
            nonZero.isSelected = filter == BudgetPlanHelper.ListFilter.NON_ZERO
            overspend.isSelected = filter == BudgetPlanHelper.ListFilter.OVERSPEND
            maybeShowFilterHint()
            displayCategories(manager.getCategoriesForBudget())
        }
        all.setOnClickListener { select(BudgetPlanHelper.ListFilter.ALL) }
        nonZero.setOnClickListener { select(BudgetPlanHelper.ListFilter.NON_ZERO) }
        overspend.setOnClickListener { select(BudgetPlanHelper.ListFilter.OVERSPEND) }
        all.isSelected = true
    }

    private fun maybeShowFilterHint() {
        val prefs = getSharedPreferences(PREFS_BUDGET_FILTERS, MODE_PRIVATE)
        if (prefs.getBoolean(KEY_FILTER_HINT_SHOWN, false)) return
        prefs.edit().putBoolean(KEY_FILTER_HINT_SHOWN, true).apply()
        Toast.makeText(this, R.string.budget_filter_hint, Toast.LENGTH_LONG).show()
    }

    private fun reload() {
        lifecycleScope.launch {
            manager.getCategoriesAsync(forceReload = true)
            val profiles = manager.getBudgetProfilesAsync()
            val activeId = manager.getActiveBudgetId()
            val total = manager.getTotalBalance(activeId)
            activeBudgetNameText.text = profiles.firstOrNull { it.id == activeId }?.name
                ?: getString(R.string.budget_profiles_default_name)
            totalBalanceText.text = MoneyFormat.formatRub(total)
            totalBalanceText.setTextColor(
                ContextCompat.getColor(
                    this@BudgetActivity,
                    if (total >= 0.0) R.color.main_hero_balance_positive else R.color.main_hero_balance_negative,
                ),
            )
            val daily = BudgetPlanHelper.safeToSpendDaily(total)
            if (daily != null) {
                safeToSpendText.visibility = View.VISIBLE
                safeToSpendText.text = getString(
                    R.string.budget_safe_to_spend,
                    MoneyFormat.format(daily),
                    BudgetPlanHelper.daysLeftInMonth(),
                )
            } else {
                safeToSpendText.visibility = View.GONE
            }
            loadMonthlyMaps(activeId, manager.getCategoriesForBudget(activeId))
            displayCategories(manager.getCategoriesForBudget(activeId))
        }
    }

    private suspend fun loadMonthlyMaps(budgetId: Int, categories: List<BudgetCategory>) {
        val dao = BudgetDatabase.getInstance(this).budgetDao()
        val monthStart = BudgetPlanHelper.monthStartMillis()
        val current = MonthlyPlanHelper.currentMonth()
        val spent = dao.getExpenseSumsSince(monthStart).associate { it.categoryId to it.total }
        val plans = dao.getMonthlyPlansForBudgetMonth(budgetId, current.year, current.month)
            .associateBy { it.categoryId }
        val leaves = categories.filter { it.isActive && !manager.hasSubcategories(it.id) }
        monthlySpentMap = leaves.associate { it.id to (spent[it.id] ?: 0.0) }
        monthlyPlannedMap = leaves.associate { leaf ->
            leaf.id to MonthlyPlanHelper.effectivePlannedAmount(leaf, plans[leaf.id])
        }
    }

    private fun toggleExpandAll() {
        val parents = manager.getRootCategories().filter { manager.hasSubcategories(it.id) }.map { it.id }
        if (parents.isEmpty()) return
        if (expandedParentIds.containsAll(parents)) {
            expandedParentIds.clear()
        } else {
            expandedParentIds.addAll(parents)
        }
        displayCategories(manager.getCategoriesForBudget())
    }

    private fun displayCategories(categories: List<BudgetCategory>) {
        categoriesContainer.removeAllViews()
        val parents = categories.filter { it.parentId == 0 && it.isActive }.sortedBy { it.position }
        findViewById<View>(R.id.budgetLoadingState).visibility = View.GONE
        findViewById<View>(R.id.budgetErrorState).visibility = View.GONE
        if (parents.isEmpty()) {
            categoriesScrollView.visibility = View.GONE
            findViewById<View>(R.id.budgetEmptyState).visibility = View.VISIBLE
            return
        }
        findViewById<View>(R.id.budgetEmptyState).visibility = View.GONE
        categoriesScrollView.visibility = View.VISIBLE
        expandedParentIds.retainAll(parents.map { it.id }.toSet())

        val leaves = categories.filter { it.isActive && !manager.hasSubcategories(it.id) }
        val threshold = OverspendPreferences.getThresholdPercent(this)
        findViewById<TextView>(R.id.budgetFilterAll).text =
            getString(R.string.budget_filter_all_count, leaves.size)
        findViewById<TextView>(R.id.budgetFilterNonZero).text =
            getString(
                R.string.budget_filter_active_count,
                leaves.count { leaf ->
                    BudgetPlanHelper.matchesFilter(
                        leaf,
                        monthlySpentMap[leaf.id] ?: 0.0,
                        BudgetPlanHelper.ListFilter.NON_ZERO,
                        threshold,
                    )
                },
            )
        findViewById<TextView>(R.id.budgetFilterOverspend).text =
            getString(
                R.string.budget_filter_overspend_count,
                leaves.count { leaf ->
                    BudgetPlanHelper.isCategoryOverspent(
                        leaf,
                        monthlySpentMap[leaf.id] ?: 0.0,
                        threshold,
                        monthlyPlannedMap[leaf.id] ?: leaf.plannedAmount,
                    )
                },
            )

        for (parent in parents) {
            val children = categories
                .filter { it.parentId == parent.id && it.isActive }
                .sortedBy { it.position }
                .filter { shouldShowCategory(it) }
            val showParent = listFilter == BudgetPlanHelper.ListFilter.ALL || children.isNotEmpty()
            if (!showParent) continue
            addParentHeaderView(parent)
            if (expandedParentIds.contains(parent.id)) {
                for (child in children) {
                    addLeafRowView(child)
                }
            }
        }
    }

    private fun shouldShowCategory(category: BudgetCategory): Boolean {
        if (manager.hasSubcategories(category.id)) return true
        val spent = monthlySpentMap[category.id] ?: 0.0
        val threshold = OverspendPreferences.getThresholdPercent(this)
        val planned = monthlyPlannedMap[category.id] ?: category.plannedAmount
        return when (listFilter) {
            BudgetPlanHelper.ListFilter.ALL -> true
            BudgetPlanHelper.ListFilter.NON_ZERO ->
                BudgetPlanHelper.matchesFilter(category, spent, listFilter, threshold)
            BudgetPlanHelper.ListFilter.OVERSPEND ->
                BudgetPlanHelper.isCategoryOverspent(category, spent, threshold, planned)
        }
    }

    private fun addParentHeaderView(category: BudgetCategory) {
        val row = LayoutInflater.from(this).inflate(R.layout.item_budget_parent_header, categoriesContainer, false)
        val nameView = row.findViewById<TextView>(R.id.categoryName)
        val balanceView = row.findViewById<TextView>(R.id.categoryBalance)
        val metaView = row.findViewById<TextView>(R.id.categoryMeta)

        nameView.text = category.name
        bindColorStrip(row, category, alwaysShow = true)

        val balance = manager.getCategoryBalanceWithSubcategories(category.id)
        bindBalanceText(balanceView, balance)

        val leftover = manager.getParentRemainingBalance(category.id)
        val childCount = manager.getSubCategories(category.id).size

        val metaParts = buildList {
            if (childCount > 0) add(getString(R.string.budget_subcategories_count, childCount))
            if (leftover > 0.01) {
                add(getString(R.string.income_distribution_undistributed_warning, MoneyFormat.format(leftover)))
            }
        }
        if (metaParts.isNotEmpty()) {
            metaView.text = metaParts.joinToString(" · ")
            metaView.visibility = View.VISIBLE
        } else {
            metaView.visibility = View.GONE
        }

        row.setOnClickListener {
            if (childCount > 0) {
                if (!expandedParentIds.add(category.id)) expandedParentIds.remove(category.id)
                displayCategories(manager.getCategoriesForBudget())
            }
        }
        row.setOnLongClickListener {
            showCategoryMenu(row, category, isParent = true, childCount = childCount, leftover = leftover)
            true
        }
        categoriesContainer.addView(row)
    }

    private fun addLeafRowView(category: BudgetCategory) {
        val row = LayoutInflater.from(this).inflate(R.layout.item_budget_leaf_row, categoriesContainer, false)
        val nameView = row.findViewById<TextView>(R.id.categoryName)
        val balanceView = row.findViewById<TextView>(R.id.categoryBalance)

        nameView.text = category.name
        bindColorStrip(row, category)
        bindBalanceText(balanceView, category.currentBalance)
        bindPlanUsage(row, category)

        row.setOnClickListener {
            showCategoryMenu(row, category, isParent = false, childCount = 0, leftover = 0.0)
        }
        row.findViewById<View>(R.id.addIncomeButton).setOnClickListener {
            BudgetDialogs.showAddTransaction(this, manager, category, BudgetDialogs.TransactionKind.INCOME) { reload() }
        }
        row.findViewById<View>(R.id.addExpenseButton).setOnClickListener {
            BudgetDialogs.showAddTransaction(this, manager, category, BudgetDialogs.TransactionKind.EXPENSE) { reload() }
        }
        categoriesContainer.addView(row)
    }

    private fun bindColorStrip(row: View, category: BudgetCategory, alwaysShow: Boolean = false) {
        val strip = row.findViewById<View>(R.id.colorStrip) ?: return
        val parsedColor = if (category.colorHex.isNotBlank()) {
            runCatching { Color.parseColor(category.colorHex) }.getOrNull()
        } else {
            null
        }
        when {
            parsedColor != null -> {
                strip.visibility = View.VISIBLE
                strip.setBackgroundColor(parsedColor)
            }
            alwaysShow -> {
                strip.visibility = View.VISIBLE
                strip.setBackgroundColor(ContextCompat.getColor(this, R.color.budget_blue))
            }
            else -> strip.visibility = View.GONE
        }
    }

    private fun bindBalanceText(balanceView: TextView, balance: Double) {
        balanceView.text = MoneyFormat.formatRub(balance)
        val colorRes = when {
            balance < -0.005 -> R.color.expense_red
            balance > 0.005 -> R.color.income_green
            else -> R.color.text_secondary
        }
        balanceView.setTextColor(ContextCompat.getColor(this, colorRes))
    }

    private fun showCategoryMenu(
        anchor: View,
        category: BudgetCategory,
        isParent: Boolean,
        childCount: Int,
        leftover: Double,
    ) {
        PopupMenu(this, anchor).apply {
            menu.add(0, 1, 0, R.string.budget_open_history)
            if (isParent) {
                menu.add(0, 7, 0, R.string.budget_menu_add_subcategory)
                if (leftover > 0.01 && childCount > 0) {
                    menu.add(0, 8, 0, R.string.budget_distribute)
                }
            } else {
                menu.add(0, 5, 0, R.string.budget_transfer_subcategory)
            }
            menu.add(0, 4, 0, R.string.budget_rename)
            menu.add(0, 6, 0, R.string.budget_profiles_delete)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> openHistory(category, isParent)
                    4 -> BudgetDialogs.showEditCategory(this@BudgetActivity, manager, category) { reload() }
                    5 -> showSubcategoryTransferDialog(category)
                    6 -> BudgetDialogs.confirmDeleteCategory(
                        this@BudgetActivity,
                        manager,
                        category,
                        onDistribute = { openRemainder(it, it.currentBalance) },
                    ) { reload() }
                    7 -> BudgetDialogs.showAddCategory(this@BudgetActivity, manager, category) { parentId ->
                        expandedParentIds.add(parentId)
                        reload()
                    }
                    8 -> openRemainder(category, leftover)
                }
                true
            }
            show()
        }
    }

    private fun bindPlanUsage(categoryView: View, category: BudgetCategory) {
        val planRow = categoryView.findViewById<View>(R.id.categoryPlanRow) ?: return
        val planProgress = categoryView.findViewById<ProgressBar>(R.id.categoryPlanProgress) ?: return
        val planText = categoryView.findViewById<TextView>(R.id.categoryPlanText) ?: return
        val fromMonthly = monthlyPlannedMap[category.id] ?: 0.0
        if (category.plannedAmount <= 0.0 && fromMonthly <= 0.0) {
            planRow.visibility = View.GONE
            return
        }
        val spent = monthlySpentMap[category.id] ?: 0.0
        val planned = if (fromMonthly > 0.0) fromMonthly else category.plannedAmount
        if (planned <= 0.0) {
            planRow.visibility = View.GONE
            return
        }
        val pct = BudgetPlanHelper.planPercent(spent, planned)
        planProgress.progress = pct
        val text = StringBuilder(
            getString(R.string.budget_plan_usage, pct, MoneyFormat.formatRub(spent), MoneyFormat.formatRub(planned)),
        )
        CategoryRunwayHelper.formatRunwaySuffix(this, category.currentBalance, spent)?.let { suffix ->
            text.append(" · ").append(suffix)
        }
        planText.text = text
        val threshold = OverspendPreferences.getThresholdPercent(this)
        val color = if (BudgetPlanHelper.isOverspent(spent, planned, threshold)) {
            R.color.expense_red
        } else {
            R.color.primary_green
        }
        planProgress.progressTintList = ContextCompat.getColorStateList(this, color)
        planRow.visibility = View.VISIBLE
    }

    private fun showSubcategoryTransferDialog(from: BudgetCategory) {
        BudgetTransferDialog.show(this, manager, from.id) { reload() }
    }

    private fun openRemainder(category: BudgetCategory, available: Double) {
        if (available <= 0.01) {
            Toast.makeText(this, R.string.budget_no_funds_to_distribute, Toast.LENGTH_SHORT).show()
            return
        }
        startActivity(
            Intent(this, RemainderDistributionActivity::class.java)
                .putExtra(RemainderDistributionActivity.EXTRA_SOURCE_CATEGORY_ID, category.id)
                .putExtra(RemainderDistributionActivity.EXTRA_SOURCE_CATEGORY_NAME, category.name)
                .putExtra(RemainderDistributionActivity.EXTRA_AVAILABLE_AMOUNT, available),
        )
    }

    private fun openHistory(category: BudgetCategory, includeChildren: Boolean) {
        val ids = if (includeChildren) {
            manager.getSubCategories(category.id).map { it.id }.ifEmpty { listOf(category.id) }
        } else {
            listOf(category.id)
        }
        startActivity(
            Intent(this, TransactionsActivity::class.java)
                .putExtra(TransactionsActivity.EXTRA_CATEGORY_IDS, ids.toIntArray())
                .putExtra(TransactionsActivity.EXTRA_CATEGORY_TITLE, category.name),
        )
    }

    companion object {
        private const val PREFS_BUDGET_FILTERS = "budget_filters"
        private const val KEY_FILTER_HINT_SHOWN = "filter_hint_shown"
    }
}
