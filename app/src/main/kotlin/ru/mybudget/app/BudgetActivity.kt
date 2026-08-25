package ru.mybudget.app

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
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
        findViewById<View>(R.id.budgetHint)?.let {
            ScreenHintHelper.bind(this, it, ScreenHintHelper.Keys.BUDGET, R.string.hint_budget, showHelpLink = false)
        }

        categoriesContainer = findViewById(R.id.categoriesContainer)
        categoriesScrollView = findViewById(R.id.categoriesScrollView)
        totalBalanceText = findViewById(R.id.totalBalanceText)
        activeBudgetNameText = findViewById(R.id.activeBudgetNameText)
        safeToSpendText = findViewById(R.id.safeToSpendText)

        findViewById<View>(R.id.addCategoryButton).setOnClickListener {
            BudgetDialogs.showAddCategory(this, manager) { reload() }
        }
        findViewById<View>(R.id.refreshButton).setOnClickListener {
            reload()
            Toast.makeText(this, R.string.budget_refreshed, Toast.LENGTH_SHORT).show()
        }
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
            addCategoryView(parent, level = 0, isParent = true)
            if (expandedParentIds.contains(parent.id)) {
                for (child in children) {
                    addCategoryView(child, level = 1, isParent = false)
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

    private fun addCategoryView(category: BudgetCategory, level: Int, isParent: Boolean) {
        val card = LayoutInflater.from(this).inflate(R.layout.item_budget_category, categoriesContainer, false)
        val params = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        if (level > 0) params.marginStart = dp(16)
        params.bottomMargin = dp(8)
        card.layoutParams = params

        val nameView = card.findViewById<TextView>(R.id.categoryName)
        val balanceView = card.findViewById<TextView>(R.id.categoryBalance)
        val plannedView = card.findViewById<TextView>(R.id.categoryPlanned)
        val expandIndicator = card.findViewById<TextView>(R.id.expandIndicator)
        val buttons = card.findViewById<LinearLayout>(R.id.buttonsContainer)

        nameView.text = getString(
            if (isParent) R.string.budget_parent_name else R.string.budget_child_name,
            category.name,
        )
        if (category.colorHex.isNotBlank()) {
            runCatching {
                card.findViewById<View>(R.id.colorStrip)?.apply {
                    visibility = View.VISIBLE
                    setBackgroundColor(Color.parseColor(category.colorHex))
                }
            }
        }
        nameView.setPaddingRelative(
            nameView.paddingStart + dp(16) * level,
            nameView.paddingTop,
            nameView.paddingEnd,
            nameView.paddingBottom,
        )

        val balance = if (isParent) manager.getCategoryBalanceWithSubcategories(category.id) else category.currentBalance
        balanceView.text = getString(R.string.budget_balance_row, MoneyFormat.formatRub(balance))
        val colorRes = when {
            balance < -0.005 -> R.color.expense_red
            balance > 0.005 -> R.color.income_green
            else -> R.color.text_secondary
        }
        balanceView.setTextColor(ContextCompat.getColor(this, colorRes))

        val leftover = if (isParent) manager.getParentRemainingBalance(category.id) else 0.0
        val childCount = manager.getSubCategories(category.id).size
        if (isParent) {
            val leftoverHint = if (leftover > 0.01) {
                " · ${getString(R.string.income_distribution_undistributed_warning, MoneyFormat.format(leftover))}"
            } else {
                ""
            }
            plannedView.text = getString(R.string.budget_subcategories_count, childCount) + leftoverHint
            if (childCount > 0) {
                expandIndicator.visibility = View.VISIBLE
                val expanded = expandedParentIds.contains(category.id)
                expandIndicator.text = getString(if (expanded) R.string.ui_chevron_down else R.string.ui_chevron_right)
            } else {
                expandIndicator.visibility = View.GONE
            }
        } else {
            plannedView.text = ""
            expandIndicator.visibility = View.GONE
        }

        val buttonParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { marginStart = dp(4) }

        fun iconButton(text: String, colorRes: Int): MaterialButton {
            return MaterialButton(this, null, com.google.android.material.R.attr.materialButtonStyle).apply {
                this.text = text
                setTextColor(ContextCompat.getColor(this@BudgetActivity, colorRes))
                setPadding(dp(6), dp(2), dp(6), dp(2))
                textSize = 12f
                isAllCaps = false
                minHeight = dp(36)
                insetTop = 0
                insetBottom = 0
                backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this@BudgetActivity, android.R.color.transparent))
                layoutParams = buttonParams
            }
        }

        if (isParent) {
            val addSub = iconButton(getString(R.string.ui_add_sub), R.color.budget_blue)
            addSub.setOnClickListener {
                BudgetDialogs.showAddCategory(this, manager, category) { parentId ->
                    expandedParentIds.add(parentId)
                    reload()
                }
            }
            buttons.addView(addSub)
            val income = iconButton(getString(R.string.ui_add), R.color.income_green)
            income.setOnClickListener {
                BudgetDialogs.showAddTransaction(this, manager, category, BudgetDialogs.TransactionKind.INCOME) { reload() }
            }
            buttons.addView(income)
            if (leftover > 0.01 && childCount > 0) {
                val distribute = iconButton(getString(R.string.budget_distribute), R.color.income_green)
                distribute.setOnClickListener { openRemainder(category, leftover) }
                buttons.addView(distribute)
            }
        } else {
            val income = iconButton(getString(R.string.ui_add), R.color.income_green)
            income.setOnClickListener {
                BudgetDialogs.showAddTransaction(this, manager, category, BudgetDialogs.TransactionKind.INCOME) { reload() }
            }
            val expense = iconButton(getString(R.string.ui_expense_minus), R.color.expense_red)
            expense.setOnClickListener {
                BudgetDialogs.showAddTransaction(this, manager, category, BudgetDialogs.TransactionKind.EXPENSE) { reload() }
            }
            buttons.addView(income)
            buttons.addView(expense)
            val transfer = iconButton(getString(R.string.budget_transfer_subcategory), R.color.budget_blue)
            transfer.setOnClickListener { showSubcategoryTransferDialog(category) }
            buttons.addView(transfer)
        }
        val edit = iconButton(getString(R.string.ui_edit), R.color.budget_blue)
        edit.setOnClickListener {
            BudgetDialogs.showEditCategory(this, manager, category) { reload() }
        }
        val delete = iconButton(getString(R.string.ui_delete), R.color.text_secondary)
        delete.setOnClickListener {
            BudgetDialogs.confirmDeleteCategory(
                this,
                manager,
                category,
                onDistribute = { openRemainder(it, it.currentBalance) },
            ) { reload() }
        }
        buttons.addView(edit)
        buttons.addView(delete)
        bindPlanUsage(card, category)

        val openHistory = View.OnClickListener {
            if (isParent) {
                if (childCount > 0) {
                    if (!expandedParentIds.add(category.id)) expandedParentIds.remove(category.id)
                    displayCategories(manager.getCategoriesForBudget())
                } else {
                    openHistory(category, includeChildren = true)
                }
            } else {
                openHistory(category, includeChildren = false)
            }
        }
        nameView.setOnClickListener(openHistory)
        expandIndicator.setOnClickListener(openHistory)
        nameView.setOnLongClickListener {
            PopupMenu(this, nameView).apply {
                menu.add(0, 1, 0, R.string.budget_open_history)
                menu.add(0, 2, 0, R.string.budget_quick_income)
                menu.add(0, 3, 0, R.string.budget_quick_expense)
                menu.add(0, 4, 0, R.string.budget_rename)
                if (!isParent) {
                    menu.add(0, 5, 0, R.string.budget_transfer_subcategory)
                }
                menu.add(0, 6, 0, R.string.budget_profiles_delete)
                setOnMenuItemClickListener { item ->
                    when (item.itemId) {
                        1 -> openHistory(category, isParent)
                        2 -> BudgetDialogs.showAddTransaction(
                            this@BudgetActivity,
                            manager,
                            category,
                            BudgetDialogs.TransactionKind.INCOME,
                        ) { reload() }
                        3 -> BudgetDialogs.showAddTransaction(
                            this@BudgetActivity,
                            manager,
                            category,
                            BudgetDialogs.TransactionKind.EXPENSE,
                        ) { reload() }
                        4 -> BudgetDialogs.showEditCategory(this@BudgetActivity, manager, category) { reload() }
                        5 -> showSubcategoryTransferDialog(category)
                        6 -> BudgetDialogs.confirmDeleteCategory(
                            this@BudgetActivity,
                            manager,
                            category,
                            onDistribute = { openRemainder(it, it.currentBalance) },
                        ) { reload() }
                    }
                    true
                }
                show()
            }
            true
        }
        categoriesContainer.addView(card)
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

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val PREFS_BUDGET_FILTERS = "budget_filters"
        private const val KEY_FILTER_HINT_SHOWN = "filter_hint_shown"
    }
}
