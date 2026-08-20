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
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.launch

class BudgetActivity : AppCompatActivity() {
    private enum class ListFilter { ALL, NON_ZERO, OVERSPEND }

    private lateinit var manager: BudgetManager
    private lateinit var categoriesContainer: LinearLayout
    private lateinit var categoriesScrollView: ScrollView
    private lateinit var totalBalanceText: TextView
    private lateinit var activeBudgetNameText: TextView
    private val expandedParentIds = mutableSetOf<Int>()
    private var listFilter = ListFilter.ALL

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_budget)
        manager = BudgetManager.getInstance(this)
        ScreenHeaderHelper.setup(this, getString(R.string.budget_screen_title))
        findViewById<View>(R.id.budgetHint)?.let {
            ScreenHintHelper.bind(this, it, ScreenHintHelper.Keys.BUDGET, R.string.hint_budget, showHelpLink = false)
        }

        categoriesContainer = findViewById(R.id.categoriesContainer)
        categoriesScrollView = findViewById(R.id.categoriesScrollView)
        totalBalanceText = findViewById(R.id.totalBalanceText)
        activeBudgetNameText = findViewById(R.id.activeBudgetNameText)

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
        fun select(filter: ListFilter) {
            listFilter = filter
            all.isSelected = filter == ListFilter.ALL
            nonZero.isSelected = filter == ListFilter.NON_ZERO
            overspend.isSelected = filter == ListFilter.OVERSPEND
            displayCategories(manager.getCategoriesForBudget())
        }
        all.setOnClickListener { select(ListFilter.ALL) }
        nonZero.setOnClickListener { select(ListFilter.NON_ZERO) }
        overspend.setOnClickListener { select(ListFilter.OVERSPEND) }
        all.isSelected = true
    }

    private fun reload() {
        lifecycleScope.launch {
            manager.getCategoriesAsync(forceReload = true)
            val profiles = manager.getBudgetProfilesAsync()
            val activeId = manager.getActiveBudgetId()
            activeBudgetNameText.text = profiles.firstOrNull { it.id == activeId }?.name
                ?: getString(R.string.budget_profiles_default_name)
            totalBalanceText.text = MoneyFormat.formatRub(manager.getTotalBalance(activeId))
            displayCategories(manager.getCategoriesForBudget(activeId))
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
        findViewById<TextView>(R.id.budgetFilterAll).text =
            getString(R.string.budget_filter_all_count, leaves.size)
        findViewById<TextView>(R.id.budgetFilterNonZero).text =
            getString(R.string.budget_filter_active_count, leaves.count { kotlin.math.abs(it.currentBalance) > 0.005 })
        findViewById<TextView>(R.id.budgetFilterOverspend).text =
            getString(R.string.budget_filter_overspend_count, leaves.count { it.currentBalance < -0.005 })

        for (parent in parents) {
            val children = categories
                .filter { it.parentId == parent.id && it.isActive }
                .sortedBy { it.position }
                .filter { shouldShowLeaf(it) }
            val showParent = listFilter == ListFilter.ALL || children.isNotEmpty() || shouldShowLeaf(parent)
            if (!showParent) continue
            addCategoryView(parent, level = 0, isParent = true)
            if (expandedParentIds.contains(parent.id)) {
                for (child in children) {
                    addCategoryView(child, level = 1, isParent = false)
                }
            }
        }
    }

    private fun shouldShowLeaf(category: BudgetCategory): Boolean {
        if (manager.hasSubcategories(category.id)) return listFilter == ListFilter.ALL
        return when (listFilter) {
            ListFilter.ALL -> true
            ListFilter.NON_ZERO -> kotlin.math.abs(category.currentBalance) > 0.005
            ListFilter.OVERSPEND -> category.currentBalance < -0.005
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

        val childCount = manager.getSubCategories(category.id).size
        if (isParent) {
            plannedView.text = getString(R.string.budget_subcategories_count, childCount)
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
        }
        val edit = iconButton(getString(R.string.ui_edit), R.color.budget_blue)
        edit.setOnClickListener {
            BudgetDialogs.showEditCategory(this, manager, category) { reload() }
        }
        val delete = iconButton(getString(R.string.ui_delete), R.color.text_secondary)
        delete.setOnClickListener {
            BudgetDialogs.confirmDeleteCategory(this, manager, category) { reload() }
        }
        buttons.addView(edit)
        buttons.addView(delete)

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
                menu.add(0, 2, 0, R.string.budget_rename)
                menu.add(0, 3, 0, R.string.budget_profiles_delete)
                setOnMenuItemClickListener { item ->
                    when (item.itemId) {
                        1 -> openHistory(category, isParent)
                        2 -> BudgetDialogs.showEditCategory(this@BudgetActivity, manager, category) { reload() }
                        3 -> BudgetDialogs.confirmDeleteCategory(this@BudgetActivity, manager, category) { reload() }
                    }
                    true
                }
                show()
            }
            true
        }
        categoriesContainer.addView(card)
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
}
