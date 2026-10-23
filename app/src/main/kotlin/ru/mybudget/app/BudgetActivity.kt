package ru.mybudget.app

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.DragEvent
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.ImageButton
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import ru.mybudget.app.data.BudgetDatabase
import ru.mybudget.app.setup.OverspendPreferences

class BudgetActivity : AppCompatActivity() {
    private lateinit var manager: BudgetManager
    private lateinit var categoriesContainer: LinearLayout
    private lateinit var categoriesScrollView: ScrollView
    private var showBudgetDetails=false
    private lateinit var totalBalanceText: TextView
    private lateinit var activeBudgetNameText: TextView
    private lateinit var safeToSpendText: TextView
    private val expandedParentIds = mutableSetOf<Int>()
    private var listFilter = BudgetPlanHelper.ListFilter.ALL
    private var monthlySpentMap: Map<Int, Double> = emptyMap()
    private var monthlyPlannedMap: Map<Int, Double> = emptyMap()
    @Volatile
    private var isDataRefreshInProgress = false
    private var draggedRow: View? = null
    private var draggedCategory: BudgetCategory? = null
    private val rowCategories = mutableMapOf<View, BudgetCategory>()
    private var reorderMode = false
    private var filterBeforeReorder = BudgetPlanHelper.ListFilter.ALL
    private var reorderSaving = false
    private var restoredScrollY: Int? = null
    private lateinit var reorderBackCallback: OnBackPressedCallback

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_budget)
        manager = BudgetManager.getInstance(this)
        showBudgetDetails=savedInstanceState?.getBoolean("budget_details") ?: false
        expandedParentIds.addAll(savedInstanceState?.getIntArray("budget_expanded")?.toList().orEmpty())
        listFilter = savedInstanceState?.getString("budget_filter")?.let {
            runCatching { BudgetPlanHelper.ListFilter.valueOf(it) }.getOrNull()
        } ?: BudgetPlanHelper.ListFilter.ALL
        filterBeforeReorder = savedInstanceState?.getString("budget_previous_filter")?.let {
            runCatching { BudgetPlanHelper.ListFilter.valueOf(it) }.getOrNull()
        } ?: listFilter
        reorderMode = savedInstanceState?.getBoolean("budget_reorder") ?: false
        restoredScrollY = savedInstanceState?.getInt("budget_scroll")
        reorderBackCallback = object : OnBackPressedCallback(reorderMode) {
            override fun handleOnBackPressed() { setReorderMode(false) }
        }
        onBackPressedDispatcher.addCallback(this, reorderBackCallback)
        ScreenHeaderHelper.setup(this, getString(R.string.budget_screen_title))
        TopLevelNavigation.bind(this, TopLevelNavigation.Destination.BUDGET)
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
        findViewById<View>(R.id.budgetDetailsToggle).setOnClickListener {
            showBudgetDetails=!showBudgetDetails
            updateBudgetDetails()
            displayCategories(manager.getCategoriesForBudget())
        }
        updateBudgetDetails()

        findViewById<View>(R.id.activeBudgetPicker).setOnClickListener {
            BudgetPicker.show(this, onSwitched = {
                expandedParentIds.clear()
                setReorderMode(false)
                categoriesScrollView.scrollTo(0, 0)
                reload()
            })
        }
        findViewById<View>(R.id.budgetExpandToggle).setOnClickListener { toggleExpandAll() }
        findViewById<View>(R.id.budgetOrderButton).setOnClickListener { setReorderMode(!reorderMode) }
        categoriesContainer.setOnDragListener(dragListener)
        setupFilters()
        updateReorderControls()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                manager.categoriesFlow.collect {
                    if (!isDataRefreshInProgress) refresh()
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("budget_details",showBudgetDetails)
        outState.putIntArray("budget_expanded", expandedParentIds.toIntArray())
        outState.putString("budget_filter", listFilter.name)
        outState.putString("budget_previous_filter", filterBeforeReorder.name)
        outState.putBoolean("budget_reorder", reorderMode)
        outState.putInt("budget_scroll", categoriesScrollView.scrollY)
        super.onSaveInstanceState(outState)
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
            if (reorderMode) return
            listFilter = filter
            all.isSelected = filter == BudgetPlanHelper.ListFilter.ALL
            nonZero.isSelected = filter == BudgetPlanHelper.ListFilter.NON_ZERO
            overspend.isSelected = filter == BudgetPlanHelper.ListFilter.OVERSPEND
            maybeShowFilterHint()
            displayCategories(manager.getCategoriesForBudget())
            categoriesScrollView.post { categoriesScrollView.scrollTo(0, 0) }
        }
        all.setOnClickListener { select(BudgetPlanHelper.ListFilter.ALL) }
        nonZero.setOnClickListener { select(BudgetPlanHelper.ListFilter.NON_ZERO) }
        overspend.setOnClickListener { select(BudgetPlanHelper.ListFilter.OVERSPEND) }
        updateFilterSelection()
    }

    private fun updateFilterSelection() {
        listOf(R.id.budgetFilterAll, R.id.budgetFilterNonZero, R.id.budgetFilterOverspend)
            .zip(BudgetPlanHelper.ListFilter.entries).forEach { (id, filter) ->
                findViewById<View>(id).apply {
                    isSelected = filter == listFilter
                    isEnabled = !reorderMode
                    alpha = if (reorderMode) 0.5f else 1f
                }
            }
    }

    private fun setReorderMode(enabled: Boolean) {
        if (reorderSaving || reorderMode == enabled) return
        if (enabled) {
            filterBeforeReorder = listFilter
            listFilter = BudgetPlanHelper.ListFilter.ALL
        } else {
            listFilter = filterBeforeReorder
        }
        reorderMode = enabled
        reorderBackCallback.isEnabled = enabled
        updateReorderControls()
        displayCategories(manager.getCategoriesForBudget())
    }

    private fun updateReorderControls() {
        findViewById<TextView>(R.id.budgetOrderButton).setText(
            if (reorderMode) R.string.budget_order_done else R.string.budget_order,
        )
        findViewById<View>(R.id.budgetOrderHint).visibility = if (reorderMode) View.VISIBLE else View.GONE
        updateFilterSelection()
    }

    private fun maybeShowFilterHint() {
        val prefs = getSharedPreferences(PREFS_BUDGET_FILTERS, MODE_PRIVATE)
        if (prefs.getBoolean(KEY_FILTER_HINT_SHOWN, false)) return
        prefs.edit().putBoolean(KEY_FILTER_HINT_SHOWN, true).apply()
        Toast.makeText(this, R.string.budget_filter_hint, Toast.LENGTH_LONG).show()
    }

    private fun reload() {
        isDataRefreshInProgress = true
        lifecycleScope.launch {
            try {
                manager.getCategoriesAsync(forceReload = true)
                refresh()
            } finally {
                isDataRefreshInProgress = false
            }
        }
    }

    private suspend fun refresh() {
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
            val daily = BudgetPlanHelper.safeToSpendDaily(total, reservedObligationsAmount(activeId))
            if (daily != null) {
                safeToSpendText.visibility = if(showBudgetDetails) View.VISIBLE else View.GONE
                safeToSpendText.text = getString(
                    R.string.budget_safe_to_spend,
                    MoneyFormat.format(daily),
                    BudgetPlanHelper.daysLeftInMonth(),
                )
            } else {
                safeToSpendText.text = ""
                safeToSpendText.visibility = View.GONE
            }
            loadMonthlyMaps(activeId, manager.getCategoriesForBudget(activeId))
            displayCategories(manager.getCategoriesForBudget(activeId))
    }

    private suspend fun reservedObligationsAmount(budgetId: Int): Double {
        val dao = BudgetDatabase.getInstance(this).budgetDao()
        val obligations = dao.getPlannedObligationsByBudgetOnce(budgetId)
        if (obligations.isEmpty()) return 0.0
        val payments = dao.getObligationPaymentsByBudget(budgetId)
        return ObligationPaymentHelper.unpaidPeriodAmount(obligations, payments)
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
        val parents = visibleGroups(manager.getCategoriesForBudget()).filter { it.hasChildren }.map { it.parent.id }
        if (parents.isEmpty()) return
        if (expandedParentIds.containsAll(parents)) {
            expandedParentIds.clear()
        } else {
            expandedParentIds.addAll(parents)
        }
        displayCategories(manager.getCategoriesForBudget())
    }

    private fun displayCategories(categories: List<BudgetCategory>) {
        val scrollY = restoredScrollY ?: categoriesScrollView.scrollY
        categoriesContainer.removeAllViews()
        rowCategories.clear()
        draggedRow = null
        draggedCategory = null
        val parents = categories.filter { it.parentId == 0 && it.isActive }.sortedBy { it.position }
        val groups = visibleGroups(categories)
        val expandableIds = groups.filter { it.hasChildren }.map { it.parent.id }
        findViewById<TextView>(R.id.budgetExpandToggle).apply {
            val allExpanded = expandableIds.isNotEmpty() && expandedParentIds.containsAll(expandableIds)
            setText(if (allExpanded) R.string.budget_collapse_all_label else R.string.budget_expand_all_label)
            isEnabled = expandableIds.isNotEmpty()
            alpha = if (isEnabled) 1f else 0.5f
        }
        findViewById<View>(R.id.budgetLoadingState).visibility = View.GONE
        findViewById<View>(R.id.budgetErrorState).visibility = View.GONE
        if (parents.isEmpty()) {
            findViewById<TextView>(R.id.budgetFilterAll).text = getString(R.string.budget_filter_all_count, 0)
            findViewById<TextView>(R.id.budgetFilterNonZero).text = getString(R.string.budget_filter_active_count, 0)
            findViewById<TextView>(R.id.budgetFilterOverspend).text = getString(R.string.budget_filter_overspend_count, 0)
            categoriesScrollView.visibility = View.GONE
            findViewById<TextView>(R.id.budgetEmptyState).apply {
                setText(R.string.budget_empty)
                visibility = View.VISIBLE
            }
            return
        }
        findViewById<View>(R.id.budgetEmptyState).visibility = View.GONE
        restoredScrollY = null
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

        if (groups.isEmpty()) {
            categoriesScrollView.visibility = View.GONE
            findViewById<TextView>(R.id.budgetEmptyState).apply {
                setText(R.string.budget_filter_empty)
                visibility = View.VISIBLE
            }
        }
        for (group in groups) {
            if (!group.hasChildren) {
                addLeafRowView(group.parent)
            } else {
                addParentHeaderView(group.parent)
                if (expandedParentIds.contains(group.parent.id)) group.children.forEach { addLeafRowView(it) }
            }
        }
        categoriesScrollView.post { categoriesScrollView.scrollTo(0, scrollY) }
    }

    private fun updateBudgetDetails() {
        findViewById<android.widget.ImageButton>(R.id.budgetDetailsToggle).apply {
            rotation=if(showBudgetDetails) 180f else 0f
            contentDescription=getString(if(showBudgetDetails) R.string.budget_hide_details else R.string.budget_show_details)
            androidx.appcompat.widget.TooltipCompat.setTooltipText(this,contentDescription)
        }
        safeToSpendText.visibility=if(showBudgetDetails && safeToSpendText.text.isNotBlank()) View.VISIBLE else View.GONE
    }

    private fun visibleGroups(categories: List<BudgetCategory>) = BudgetListHelper.visibleGroups(
        categories, listFilter, monthlySpentMap, monthlyPlannedMap, OverspendPreferences.getThresholdPercent(this),
    )

    private fun addParentHeaderView(category: BudgetCategory) {
        val row = LayoutInflater.from(this).inflate(R.layout.item_budget_parent_header, categoriesContainer, false)
        val nameView = row.findViewById<TextView>(R.id.categoryName)
        val balanceView = row.findViewById<TextView>(R.id.categoryBalance)
        val metaView = row.findViewById<TextView>(R.id.categoryMeta)

        nameView.text = category.name
        bindColorStrip(row, category)

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
            metaView.visibility = if(showBudgetDetails) View.VISIBLE else View.GONE
        } else {
            metaView.visibility = View.GONE
        }

        val toggle = {
            if (!expandedParentIds.add(category.id)) expandedParentIds.remove(category.id)
            displayCategories(manager.getCategoriesForBudget())
        }
        row.setOnClickListener { toggle() }
        row.findViewById<ImageButton>(R.id.categoryExpandButton).apply {
            rotation = if (category.id in expandedParentIds) 180f else 0f
            contentDescription = getString(
                if (category.id in expandedParentIds) R.string.budget_collapse_named else R.string.budget_expand_named,
                category.name,
            )
            setOnClickListener { toggle() }
        }
        bindCategoryMenu(row, category, isParent = true, childCount = childCount, leftover = leftover)
        row.setOnLongClickListener {
            if (reorderMode) showOrderMenu(row, category)
            else showCategoryMenu(row, category, isParent = true, childCount = childCount, leftover = leftover)
            true
        }
        bindDragHandle(row, category)
        categoriesContainer.addView(row)
    }

    private fun addLeafRowView(category: BudgetCategory) {
        val row = LayoutInflater.from(this).inflate(R.layout.item_budget_leaf_row, categoriesContainer, false)
        val nameView = row.findViewById<TextView>(R.id.categoryName)
        val balanceView = row.findViewById<TextView>(R.id.categoryBalance)
        if (category.parentId == 0) {
            (row.layoutParams as LinearLayout.LayoutParams).apply {
                marginStart = 0
                topMargin = resources.getDimensionPixelSize(R.dimen.space_8)
            }
            row.setBackgroundResource(R.drawable.budget_parent_header_bg)
        }

        nameView.text = category.name
        bindColorStrip(row, category)
        bindBalanceText(balanceView, category.currentBalance)
        bindPlanUsage(row, category)

        row.setOnClickListener {
            if (reorderMode) showOrderMenu(row, category)
            else showCategoryMenu(row, category, isParent = category.parentId == 0, childCount = 0, leftover = 0.0)
        }
        bindCategoryMenu(row, category, isParent = category.parentId == 0, childCount = 0, leftover = 0.0)
        row.findViewById<View>(R.id.categoryQuickActions).visibility = if (reorderMode || !showBudgetDetails) View.GONE else View.VISIBLE
        row.findViewById<View>(R.id.addIncomeButton).setOnClickListener {
            BudgetDialogs.showAddTransaction(this, manager, category, BudgetDialogs.TransactionKind.INCOME) { reload() }
        }
        row.findViewById<View>(R.id.addExpenseButton).setOnClickListener {
            BudgetDialogs.showAddTransaction(this, manager, category, BudgetDialogs.TransactionKind.EXPENSE) { reload() }
        }
        bindDragHandle(row, category)
        categoriesContainer.addView(row)
    }

    private fun bindCategoryMenu(row: View, category: BudgetCategory, isParent: Boolean, childCount: Int, leftover: Double) {
        row.findViewById<View>(R.id.categoryMenuButton).apply {
            visibility = if (reorderMode) View.GONE else View.VISIBLE
            contentDescription = getString(R.string.budget_category_actions, category.name)
            setOnClickListener { showCategoryMenu(this, category, isParent, childCount, leftover) }
        }
    }

    private fun bindDragHandle(row: View, category: BudgetCategory) {
        rowCategories[row] = category
        val handle = row.findViewById<View>(R.id.dragHandle) ?: return
        handle.visibility = if (reorderMode) View.VISIBLE else View.GONE
        handle.contentDescription = getString(R.string.budget_order_named, category.name)
        handle.setOnClickListener {
            showOrderMenu(handle, category)
        }
        handle.setOnLongClickListener {
            startCategoryDrag(row, category)
            true
        }
    }

    private fun startCategoryDrag(row: View, category: BudgetCategory) {
        if (!reorderMode || reorderSaving) return
        draggedRow = row
        draggedCategory = category
        row.startDragAndDrop(null, View.DragShadowBuilder(row), category.id, 0)
    }

    private val dragListener = View.OnDragListener { _, event ->
        when (event.action) {
            DragEvent.ACTION_DRAG_STARTED -> {
                if (!reorderMode || reorderSaving) return@OnDragListener false
                val categoryId = event.localState as? Int
                val category = categoryId?.let { id -> rowCategories.values.firstOrNull { it.id == id } }
                draggedCategory = category
                draggedRow = rowCategories.entries.firstOrNull { it.value.id == categoryId }?.key
                category != null && draggedRow != null
            }
            DragEvent.ACTION_DRAG_LOCATION -> {
                val edge = resources.getDimensionPixelSize(R.dimen.space_24) * 2
                val visibleY = event.y - categoriesScrollView.scrollY
                val step = resources.getDimensionPixelSize(R.dimen.space_12)
                if (visibleY < edge) categoriesScrollView.scrollBy(0, -step)
                else if (visibleY > categoriesScrollView.height - edge) categoriesScrollView.scrollBy(0, step)
                moveDraggedRowIfNeeded(event.x, event.y)
                true
            }
            DragEvent.ACTION_DROP -> {
                dropDraggedRow()
                true
            }
            DragEvent.ACTION_DRAG_ENDED -> {
                if (!event.result) displayCategories(manager.getCategoriesForBudget())
                draggedRow = null
                draggedCategory = null
                true
            }
            else -> true
        }
    }

    private fun moveDraggedRowIfNeeded(x: Float, y: Float) {
        val drag = draggedRow ?: return
        val dragCategory = draggedCategory ?: return
        val target = findRowUnder(x, y) ?: return
        if (target === drag) return
        val targetCategory = rowCategories[target] ?: return
        if (!areDragSiblings(dragCategory, targetCategory)) return
        val from = categoriesContainer.indexOfChild(drag)
        val to = categoriesContainer.indexOfChild(target)
        if (from == -1 || to == -1) return
        // Move a root together with its currently expanded children.
        fun block(row: View, category: BudgetCategory): List<View> = buildList {
            add(row)
            if (category.parentId == 0) {
                var index = categoriesContainer.indexOfChild(row) + 1
                while (index < categoriesContainer.childCount) {
                    val child = categoriesContainer.getChildAt(index)
                    if (rowCategories[child]?.parentId != category.id) break
                    add(child)
                    index++
                }
            }
        }
        val moving = block(drag, dragCategory)
        val targetBlock = block(target, targetCategory)
        moving.forEach(categoriesContainer::removeView)
        val insertAt = if (from < to) categoriesContainer.indexOfChild(targetBlock.last()) + 1
            else categoriesContainer.indexOfChild(target)
        moving.forEachIndexed { index, view -> categoriesContainer.addView(view, insertAt + index) }
    }

    private fun findRowUnder(x: Float, y: Float): View? {
        for (i in 0 until categoriesContainer.childCount) {
            val child = categoriesContainer.getChildAt(i)
            if (x >= child.left && x <= child.right && y >= child.top && y <= child.bottom) return child
        }
        return null
    }

    private fun areDragSiblings(dragCategory: BudgetCategory, other: BudgetCategory): Boolean {
        return dragCategory.parentId == other.parentId && dragCategory.budgetId == other.budgetId
    }

    private fun dropDraggedRow() {
        val drag = draggedRow ?: return
        val dragCategory = draggedCategory ?: return
        val dragIndex = categoriesContainer.indexOfChild(drag)
        if (dragIndex == -1) return
        var targetIndex = 0
        for (i in 0 until dragIndex) {
            val sibling = rowCategories[categoriesContainer.getChildAt(i)] ?: continue
            if (areDragSiblings(dragCategory, sibling)) targetIndex++
        }
        saveOrder { manager.moveCategoryToPosition(dragCategory.id, targetIndex) }
    }

    private fun showOrderMenu(anchor: View, category: BudgetCategory) {
        if (!reorderMode || reorderSaving) return
        val siblings = manager.getCategoriesForBudget().filter { it.isActive && it.parentId == category.parentId }
            .sortedBy { it.position }
        val index = siblings.indexOfFirst { it.id == category.id }
        PopupMenu(this, anchor).apply {
            menu.add(0, 1, 0, R.string.budget_move_up).isEnabled = index > 0
            menu.add(0, 2, 1, R.string.budget_move_down).isEnabled = index >= 0 && index < siblings.lastIndex
            setOnMenuItemClickListener {
                saveOrder { manager.moveCategory(category.id, if (it.itemId == 1) -1 else 1) }
                true
            }
            show()
        }
    }

    private fun saveOrder(move: suspend () -> Boolean) {
        if (reorderSaving) return
        reorderSaving = true
        isDataRefreshInProgress = true
        lifecycleScope.launch {
            try {
                move()
                refresh()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                Toast.makeText(this@BudgetActivity, R.string.budget_order_failed, Toast.LENGTH_LONG).show()
                displayCategories(manager.getCategoriesForBudget())
            } finally {
                reorderSaving = false
                isDataRefreshInProgress = false
            }
        }
    }

    private fun bindColorStrip(row: View, category: BudgetCategory) {
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
            else -> strip.visibility = View.GONE
        }
    }

    private fun bindBalanceText(balanceView: TextView, balance: Double) {
        balanceView.text = getString(R.string.budget_row_balance, MoneyFormat.formatRub(balance))
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
            if(childCount==0) {
                menu.add(0,11,0,R.string.main_primary_income)
                menu.add(0,12,0,R.string.main_primary_expense)
            }
            menu.add(0, 1, 0, R.string.budget_open_history)
            if (isParent) {
                menu.add(0, 7, 0, R.string.budget_menu_add_subcategory)
                if (leftover > 0.01 && childCount > 0) {
                    menu.add(0, 8, 0, R.string.budget_distribute)
                }
            } else {
                menu.add(0, 5, 0, R.string.budget_transfer_subcategory)
                menu.add(0, 9, 0, R.string.budget_distribute_to_subcategories)
                menu.add(0, 10, 0, R.string.budget_move_subcategory)
            }
            menu.add(0, 4, 0, R.string.budget_rename)
            menu.add(0, 6, 0, R.string.budget_profiles_delete)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    11 -> BudgetDialogs.showAddTransaction(this@BudgetActivity,manager,category,BudgetDialogs.TransactionKind.INCOME) { reload() }
                    12 -> BudgetDialogs.showAddTransaction(this@BudgetActivity,manager,category,BudgetDialogs.TransactionKind.EXPENSE) { reload() }
                    1 -> openHistory(category, isParent)
                    4 -> BudgetDialogs.showEditCategory(this@BudgetActivity, manager, category) { reload() }
                    5 -> showSubcategoryTransferDialog(category)
                    9 -> openSubcategoryDistribution(category)
                    10 -> MoveSubcategoryDialog.show(this@BudgetActivity, manager, category) { reload() }
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
        val spent = monthlySpentMap[category.id] ?: 0.0
        val planned = monthlyPlannedMap[category.id] ?: category.plannedAmount
        if (planned <= 0.0) {
            planRow.visibility = View.GONE
            return
        }
        val pct = BudgetPlanHelper.planPercent(spent, planned)
        planProgress.progress = pct
        val text = StringBuilder(
            getString(R.string.budget_row_usage, MoneyFormat.formatRub(spent), MoneyFormat.formatRub(planned)),
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
        planRow.visibility = if(showBudgetDetails || BudgetPlanHelper.isOverspent(spent,planned,threshold)) View.VISIBLE else View.GONE
    }

    private fun showSubcategoryTransferDialog(from: BudgetCategory) {
        BudgetTransferDialog.show(this, manager, from.id) { reload() }
    }

    private fun openSubcategoryDistribution(category: BudgetCategory) {
        if (category.currentBalance <= 0.01) {
            Toast.makeText(this, R.string.budget_no_funds_to_distribute, Toast.LENGTH_SHORT).show()
            return
        }
        startActivity(
            Intent(this, SubcategoryDistributionActivity::class.java)
                .putExtra(SubcategoryDistributionActivity.EXTRA_SOURCE_CATEGORY_ID, category.id)
                .putExtra(SubcategoryDistributionActivity.EXTRA_SOURCE_CATEGORY_NAME, category.name)
                .putExtra(SubcategoryDistributionActivity.EXTRA_AVAILABLE_AMOUNT, category.currentBalance),
        )
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
