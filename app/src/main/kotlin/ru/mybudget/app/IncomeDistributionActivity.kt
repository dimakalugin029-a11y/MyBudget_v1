package ru.mybudget.app

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.mybudget.app.setup.ObligationPreferences
import ru.mybudget.app.setup.PendingDistributionPreferences
import kotlin.math.abs
import kotlin.math.max

class IncomeDistributionActivity : AppCompatActivity() {
    private lateinit var manager: BudgetManager
    private lateinit var adapter: DistributionLinesAdapter
    private lateinit var totalText: TextView
    private lateinit var submitButton: MaterialButton
    private lateinit var emptyText: TextView
    private var totalIncome = 0.0
    private var selectedIds = mutableListOf<Int>()
    private val amounts = mutableMapOf<Int, Double>()
    private var leaves: List<BudgetCategory> = emptyList()
    private var parents: Map<Int, String> = emptyMap()
    private var actionsSheetBehavior: BottomSheetBehavior<View>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_income_distribution)
        manager = BudgetManager.getInstance(this)
        val pending = PendingDistributionPreferences.getPending(this)
        totalIncome = intent.getDoubleExtra(EXTRA_TOTAL_INCOME, pending?.amount ?: 0.0)
        ScreenHeaderHelper.setup(this, getString(R.string.transaction_income_distribution), "⚖️")
        findViewById<View>(R.id.incomeDistributionHint)?.let {
            ScreenHintHelper.bind(
                this,
                it,
                ScreenHintHelper.Keys.INCOME_DISTRIBUTION,
                R.string.hint_income_distribution,
                showHelpLink = false,
            )
        }
        totalText = findViewById(R.id.totalAmountText)
        submitButton = findViewById(R.id.distributeButton)
        emptyText = findViewById(R.id.emptyText)
        adapter = DistributionLinesAdapter(
            onAmountChanged = { id, amount ->
                if (amount > 0.0) amounts[id] = MoneyFormat.roundMoney(amount) else amounts.remove(id)
                updateTotals()
            },
            onRemove = { id ->
                selectedIds.remove(id)
                amounts.remove(id)
                refreshList()
            },
            onAmountFocused = { collapseActionsSheet() },
        )
        findViewById<RecyclerView>(R.id.categoriesRecyclerView).apply {
            layoutManager = LinearLayoutManager(this@IncomeDistributionActivity)
            this.adapter = this@IncomeDistributionActivity.adapter
        }
        findViewById<View>(R.id.addCategoryButton).setOnClickListener { pickCategories() }
        findViewById<View>(R.id.applyDefaultsButton).setOnClickListener { applyDefaults() }
        findViewById<View>(R.id.manualEntryButton).setOnClickListener {
            findViewById<TextView>(R.id.distributionModeHint).setText(R.string.income_distribution_mode_manual)
        }
        submitButton.setOnClickListener { submit() }
        bindActionRow(R.id.incomeDistributionPlanRow, "📊", R.string.income_distribution_by_plan) {
            fillByPlan()
        }
        bindActionRow(R.id.incomeDistributionObligationsRow, "📆", R.string.income_distribution_by_obligations) {
            fillByObligations()
        }
        bindActionRow(R.id.incomeDistributionGoalsRow, "🎯", R.string.income_distribution_by_goals) {
            fillByGoals()
        }
        bindActionRow(R.id.incomeDistributionRemainderRow, "⚖️", R.string.income_distribution_remainder) {
            showRemainderDialog()
        }
        attachActionsSheet()
        lifecycleScope.launch {
            manager.getCategoriesAsync()
            leaves = manager.getCategoriesForExpenses()
            parents = manager.getRootCategories().associate { it.id to it.name }
            applyPrefill()
            if (totalIncome <= 0.0) {
                Toast.makeText(this@IncomeDistributionActivity, R.string.income_enter_total_amount, Toast.LENGTH_LONG).show()
            }
            refreshList()
        }
    }

    private fun applyPrefill() {
        val ids = intent.getIntArrayExtra(EXTRA_PREFILL_CATEGORY_IDS) ?: return
        val values = intent.getDoubleArrayExtra(EXTRA_PREFILL_AMOUNTS) ?: return
        selectedIds = ids.toMutableList()
        amounts.clear()
        ids.forEachIndexed { index, id ->
            if (index < values.size && values[index] > 0.0) {
                amounts[id] = MoneyFormat.roundMoney(values[index])
            }
        }
    }

    private fun bindActionRow(includeId: Int, icon: String, titleRes: Int, onClick: () -> Unit) {
        MenuRowHelper.bind(findViewById(includeId), icon, getString(titleRes), onClick)
    }

    private fun attachActionsSheet() {
        val sheet = findViewById<View>(R.id.incomeDistributionActionsSheet)
        val header = findViewById<View>(R.id.bottomSheetHeader)
        val chevron = findViewById<TextView>(R.id.bottomSheetChevron)
        val content = findViewById<View>(R.id.incomeDistributionContent)
        val extra = resources.getDimensionPixelSize(R.dimen.space_8)
        val fallbackPeek = resources.getDimensionPixelSize(R.dimen.touch_min_size) * 2
        CollapsibleBottomSheetHelper.attach(sheet, header, chevron, fallbackPeek)
        val behavior = BottomSheetBehavior.from(sheet)
        actionsSheetBehavior = behavior
        fun applyContentInset(peek: Int) {
            val bottom = peek + extra
            if (content.paddingBottom != bottom) {
                content.setPadding(content.paddingLeft, content.paddingTop, content.paddingRight, bottom)
            }
        }
        applyContentInset(fallbackPeek)
        header.post {
            val peek = header.height.coerceAtLeast(resources.getDimensionPixelSize(R.dimen.touch_min_size))
            behavior.peekHeight = peek
            applyContentInset(peek)
        }
        val root = findViewById<View>(R.id.incomeDistributionRoot)
        root.viewTreeObserver.addOnGlobalLayoutListener {
            val visible = android.graphics.Rect()
            root.getWindowVisibleDisplayFrame(visible)
            val covered = root.rootView.height - visible.bottom
            val imeOpen = covered > root.height / 4
            val headerPeek = header.height.coerceAtLeast(resources.getDimensionPixelSize(R.dimen.touch_min_size))
            if (imeOpen) {
                collapseActionsSheet()
                if (behavior.peekHeight != 0) {
                    behavior.peekHeight = 0
                    applyContentInset(0)
                }
            } else if (behavior.peekHeight != headerPeek) {
                behavior.peekHeight = headerPeek
                applyContentInset(headerPeek)
            }
        }
    }

    private fun collapseActionsSheet() {
        val behavior = actionsSheetBehavior ?: return
        if (behavior.state != BottomSheetBehavior.STATE_COLLAPSED &&
            behavior.state != BottomSheetBehavior.STATE_SETTLING
        ) {
            behavior.state = BottomSheetBehavior.STATE_COLLAPSED
        }
    }

    private fun pickCategories() {
        CategoryMultiPicker.show(this, leaves, parents, selectedIds.toSet()) { picked ->
            selectedIds.addAll(picked.filter { it !in selectedIds })
            refreshList()
        }
    }

    private fun fillByPlan() {
        if (leaves.isEmpty() || totalIncome <= 0.0) return
        if (selectedIds.isEmpty()) {
            val withPlan = leaves.filter { it.plannedAmount > 0.0 }.map { it.id }
            if (withPlan.isEmpty()) {
                Toast.makeText(this, R.string.income_distribution_no_defaults, Toast.LENGTH_LONG).show()
                return
            }
            selectedIds.addAll(withPlan)
        }
        val visible = selectedIds.mapNotNull { id -> leaves.firstOrNull { it.id == id } }
        if (visible.isEmpty()) return
        val totalPlan = visible.sumOf { max(it.plannedAmount, 0.0) }
        amounts.clear()
        if (totalPlan <= 0.0) {
            val part = MoneyFormat.roundMoney(totalIncome / visible.size)
            visible.forEach { amounts[it.id] = part }
        } else {
            visible.forEach { category ->
                amounts[category.id] = MoneyFormat.roundMoney(
                    (max(category.plannedAmount, 0.0) / totalPlan) * totalIncome,
                )
            }
        }
        findViewById<TextView>(R.id.distributionModeHint).setText(R.string.income_distribution_mode_plan)
        refreshList()
    }

    private fun fillByObligations() {
        if (leaves.isEmpty() || totalIncome <= 0.0) return
        lifecycleScope.launch {
            val budgetId = intent.getIntExtra(BudgetIntentExtras.BUDGET_ID, manager.getActiveBudgetId())
            val obligations = withContext(Dispatchers.IO) {
                manager.repository.getPlannedObligationsByBudgetOnce(budgetId)
            }
            val byCategory = PlannedObligationHelper.distributionByCategory(obligations)
            val unlinked = PlannedObligationHelper.unlinkedCount(obligations)
            applyFillMap(
                byCategory,
                emptyMessage = R.string.income_distribution_no_obligations,
                modeHint = R.string.income_distribution_mode_obligations,
            )
            if (byCategory.isNotEmpty() && unlinked > 0) {
                Toast.makeText(
                    this@IncomeDistributionActivity,
                    getString(R.string.income_distribution_obligations_unlinked, unlinked),
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    private fun fillByGoals() {
        if (leaves.isEmpty() || totalIncome <= 0.0) return
        lifecycleScope.launch {
            val goals = withContext(Dispatchers.IO) {
                manager.repository.getAllSavingsGoals().first().filter { it.isActive }
            }
            val balanceByCategory = leaves.associate { it.id to it.currentBalance }
            val paychecks = ObligationPreferences.getPaychecksPerMonth(this@IncomeDistributionActivity)
            val byCategory = GoalDistributionHelper.distributionByCategory(goals, balanceByCategory, paychecks)
            val fallbackCount = GoalDistributionHelper.skippedNoDeadlineCount(goals, balanceByCategory)
            applyFillMap(
                byCategory,
                emptyMessage = R.string.income_distribution_no_goals,
                modeHint = R.string.income_distribution_mode_goals,
            )
            if (byCategory.isNotEmpty() && fallbackCount > 0) {
                Toast.makeText(
                    this@IncomeDistributionActivity,
                    getString(R.string.income_distribution_goals_no_deadline, fallbackCount),
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    private fun applyFillMap(
        byCategory: Map<Int, Double>,
        emptyMessage: Int,
        modeHint: Int,
    ) {
        if (byCategory.isEmpty()) {
            Toast.makeText(this, emptyMessage, Toast.LENGTH_LONG).show()
            return
        }
        val knownIds = leaves.map { it.id }.toSet()
        selectedIds = byCategory.keys.filter { it in knownIds }.toMutableList()
        amounts.clear()
        var remaining = totalIncome
        for (id in selectedIds) {
            val suggested = byCategory[id] ?: 0.0
            val amount = minOf(suggested, remaining)
            if (amount > 0.0) {
                amounts[id] = amount
                remaining -= amount
            }
        }
        findViewById<TextView>(R.id.distributionModeHint).setText(modeHint)
        refreshList()
    }

    private fun applyDefaults() {
        val withDefaults = leaves.filter { it.defaultIncomeAmount > 0.0 }
        if (withDefaults.isEmpty()) {
            Toast.makeText(this, R.string.income_distribution_no_defaults, Toast.LENGTH_LONG).show()
            return
        }
        selectedIds = withDefaults.map { it.id }.toMutableList()
        amounts.clear()
        withDefaults.forEach { amounts[it.id] = it.defaultIncomeAmount }
        findViewById<TextView>(R.id.distributionModeHint).setText(R.string.income_distribution_mode_defaults)
        refreshList()
    }

    private fun refreshList() {
        val lines = selectedIds.mapNotNull { id ->
            val category = leaves.firstOrNull { it.id == id } ?: return@mapNotNull null
            val amount = amounts[id]
            DistributionLinesAdapter.Line(
                categoryId = id,
                title = CategoryMultiPicker.leafLabel(category, parents),
                balanceHint = getString(R.string.subcategory_balance_hint, MoneyFormat.formatRub(category.currentBalance)),
                amountText = if (amount != null && amount > 0.0) MoneyFormat.format(amount) else "",
            )
        }
        adapter.submit(lines)
        emptyText.visibility = if (lines.isEmpty()) View.VISIBLE else View.GONE
        updateTotals()
    }

    private fun distributed(): Double = amounts.values.sum()

    private fun remaining(): Double = totalIncome - distributed()

    private fun updateTotals() {
        val distributed = distributed()
        val remaining = remaining()
        if (totalIncome > 0.0 && remaining > 0.01) {
            totalText.text = getString(R.string.income_distribution_undistributed_warning, MoneyFormat.format(remaining))
            totalText.setTextColor(ContextCompat.getColor(this, R.color.expense_red))
        } else {
            totalText.text = getString(
                R.string.income_distribution_totals,
                MoneyFormat.format(distributed),
                MoneyFormat.format(maxOf(remaining, 0.0)),
            )
            totalText.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
        }
        submitButton.isEnabled = selectedIds.isNotEmpty() &&
            distributed > 0.0 &&
            (totalIncome <= 0.0 || distributed <= totalIncome + 0.01)
    }

    private fun showRemainderDialog() {
        val leftover = remaining()
        if (leftover <= 0.01) {
            Toast.makeText(this, R.string.income_distribution_no_remainder, Toast.LENGTH_SHORT).show()
            return
        }
        val visible = selectedIds.mapNotNull { id -> leaves.firstOrNull { it.id == id } }
        if (visible.isEmpty()) {
            Toast.makeText(this, R.string.distribution_add_categories_hint, Toast.LENGTH_SHORT).show()
            return
        }
        val labels = visible.map { CategoryMultiPicker.leafLabel(it, parents) }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.income_distribution_remainder_title)
            .setMessage(getString(R.string.income_distribution_remainder_msg, MoneyFormat.format(leftover)))
            .setItems(labels) { _, which ->
                val category = visible[which]
                amounts[category.id] = MoneyFormat.roundMoney((amounts[category.id] ?: 0.0) + leftover)
                refreshList()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun submit() {
        val items = amounts.filter { it.value > 0.0 }.map { it.key to it.value }
        if (items.isEmpty()) {
            Toast.makeText(this, R.string.income_distribution_empty, Toast.LENGTH_SHORT).show()
            return
        }
        if (totalIncome > 0.0 && abs(remaining()) > 0.01 && remaining() < -0.01) {
            Toast.makeText(this, R.string.income_distribution_over_limit, Toast.LENGTH_SHORT).show()
            return
        }
        val note = intent.getStringExtra(EXTRA_INCOME_NOTE)?.trim().orEmpty()
            .ifBlank { PendingDistributionPreferences.getPending(this)?.note.orEmpty() }
            .ifBlank { getString(R.string.transaction_income_distribution) }
        lifecycleScope.launch {
            manager.applyTransactionGroup(items, "income", note)
            val leftover = remaining()
            if (leftover > 0.01) {
                PendingDistributionPreferences.setPending(
                    this@IncomeDistributionActivity,
                    manager.getActiveBudgetId(),
                    leftover,
                    note,
                )
            } else {
                PendingDistributionPreferences.clear(this@IncomeDistributionActivity)
            }
            Toast.makeText(this@IncomeDistributionActivity, R.string.income_distribution_done, Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    companion object {
        const val EXTRA_INCOME_NOTE = "income_note"
        const val EXTRA_TOTAL_INCOME = "total_income"
        const val EXTRA_PREFILL_CATEGORY_IDS = "prefill_category_ids"
        const val EXTRA_PREFILL_AMOUNTS = "prefill_amounts"
    }
}
