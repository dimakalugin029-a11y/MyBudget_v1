package ru.mybudget.app

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
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
import ru.mybudget.app.setup.IncomeDistributionTemplatePreferences
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
    private var plannedSourceId: Int? = null
    private var plannedMonth = java.time.YearMonth.now()
    private var receiptRequest = java.util.UUID.randomUUID().toString()
    private var submitting = false
    private var distributionBudgetId = 1
    private val coverageDraft = OverspendCoverageDialog.Draft()
    private var coverageIds = mutableSetOf<Int>()
    private var coverageLoading = false
    private var categoriesReady = false
    private var coverageOpenedInitially = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_income_distribution)
        manager = BudgetManager.getInstance(this)
        coverageDraft.restore(savedInstanceState)
        coverageIds.addAll(savedInstanceState?.getIntArray("coverage_applied_ids")?.toList().orEmpty())
        coverageOpenedInitially = savedInstanceState?.getBoolean("coverage_initial_opened") ?: false
        val pending = PendingDistributionPreferences.getPending(this)
        distributionBudgetId = savedInstanceState?.getInt("distribution_budget")
            ?: intent.getIntExtra(BudgetIntentExtras.BUDGET_ID, manager.getActiveBudgetId())
        val matchingPending = pending?.takeIf { it.budgetId == distributionBudgetId }
        plannedSourceId = intent.getIntExtra(BudgetIntentExtras.PLANNED_INCOME_SOURCE_ID, 0).takeIf { it > 0 }
            ?: matchingPending?.plannedSourceId.takeIf { !intent.hasExtra(EXTRA_TOTAL_INCOME) }
        plannedMonth = runCatching { java.time.YearMonth.parse(intent.getStringExtra(BudgetIntentExtras.PLANNED_INCOME_MONTH)
            ?: matchingPending?.plannedMonth) }.getOrDefault(java.time.YearMonth.now())
        receiptRequest = savedInstanceState?.getString("receipt_request")
            ?: intent.getStringExtra(EXTRA_RECEIPT_REQUEST)
            ?: matchingPending?.requestId.takeIf { !intent.hasExtra(EXTRA_TOTAL_INCOME) } ?: receiptRequest
        totalIncome = intent.getDoubleExtra(EXTRA_TOTAL_INCOME, matchingPending?.amount ?: 0.0)
        if (savedInstanceState != null) {
            plannedSourceId = savedInstanceState.getInt("plan_source", 0).takeIf { it > 0 }
            plannedMonth = runCatching { java.time.YearMonth.parse(savedInstanceState.getString("plan_month")) }.getOrDefault(plannedMonth)
            totalIncome = savedInstanceState.getDouble("distribution_total", totalIncome)
        }
        ScreenHeaderHelper.setup(this, getString(R.string.transaction_income_distribution))
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
                coverageIds.remove(id)
                refreshList()
            },
            onAmountFocused = { collapseActionsSheet() },
        )
        findViewById<RecyclerView>(R.id.categoriesRecyclerView).apply {
            layoutManager = LinearLayoutManager(this@IncomeDistributionActivity)
            this.adapter = this@IncomeDistributionActivity.adapter
        }
        findViewById<View>(R.id.addCategoryButton).setOnClickListener { pickCategories() }
        submitButton.setOnClickListener { submit() }
        findViewById<View>(R.id.coverOverspendButton).apply {
            isEnabled = false
            setOnClickListener { showCoverage() }
        }
        bindActionRow(R.id.incomeDistributionPlanRow, "📊", R.string.income_distribution_by_plan) {
            fillByPlan()
        }
        bindActionRow(R.id.incomeDistributionObligationsRow, "📆", R.string.income_distribution_by_obligations) {
            fillByObligations()
        }
        bindActionRow(R.id.incomeDistributionGoalsRow, "🎯", R.string.income_distribution_by_goals) {
            fillByGoals()
        }
        bindActionRow(R.id.incomeDistributionDefaultsRow, "⭐", R.string.income_distribution_defaults) {
            applyDefaults()
        }
        bindActionRow(R.id.incomeDistributionRemainderRow, "⚖️", R.string.income_distribution_remainder) {
            showRemainderDialog()
        }
        bindActionRow(R.id.incomeDistributionTemplateRow, "🔁", R.string.income_distribution_by_template) {
            applyLastTemplate()
        }
        attachActionsSheet()
        lifecycleScope.launch {
            if (distributionBudgetId != manager.getActiveBudgetId()) {
                Toast.makeText(this@IncomeDistributionActivity, R.string.income_plan_changed, Toast.LENGTH_LONG).show()
                finish()
                return@launch
            }
            manager.getCategoriesAsync()
            leaves = manager.getCategoriesForExpenses().filter { it.isActive }
            parents = manager.getRootCategories().associate { it.id to it.name }
            applyPrefill()
            savedInstanceState?.getIntArray("distribution_ids")?.let { ids ->
                val values = savedInstanceState.getDoubleArray("distribution_amounts") ?: doubleArrayOf()
                selectedIds = ids.toMutableList()
                amounts.clear()
                ids.forEachIndexed { index, id -> values.getOrNull(index)?.let { amounts[id] = it } }
            }
            if (!intent.getBooleanExtra(EXTRA_OPEN_COVERAGE, false)) applySavedTemplateIfEmpty()
            if (totalIncome <= 0.0) {
                Toast.makeText(this@IncomeDistributionActivity, R.string.income_enter_total_amount, Toast.LENGTH_LONG).show()
            }
            categoriesReady = true
            findViewById<View>(R.id.coverOverspendButton).isEnabled = true
            if (coverageIds.isNotEmpty()) findViewById<TextView>(R.id.distributionModeHint).setText(R.string.overspend_mode)
            refreshList()
            if (coverageDraft.open || (!coverageOpenedInitially && intent.getBooleanExtra(EXTRA_OPEN_COVERAGE, false))) {
                coverageOpenedInitially = true
                showCoverage(restoreDraft = coverageDraft.open)
            }
        }
    }

    private fun showCoverage(restoreDraft: Boolean = false) {
        if (submitting || coverageLoading || !categoriesReady) return
        if (!totalIncome.isFinite() || totalIncome <= 0.0) {
            Toast.makeText(this, R.string.income_enter_total_amount, Toast.LENGTH_LONG).show()
            return
        }
        collapseActionsSheet()
        coverageLoading = true
        updateTotals()
        lifecycleScope.launch {
            try {
                require(distributionBudgetId == manager.getActiveBudgetId())
                manager.reloadCategoriesFromDatabase()
                require(distributionBudgetId == manager.getActiveBudgetId())
                val candidates = OverspendCoverage.candidates(manager.getCategories(), distributionBudgetId)
                leaves = manager.getCategoriesForExpenses().filter { it.isActive }
                parents = manager.getRootCategories().associate { it.id to it.name }
                if (candidates.isEmpty()) {
                    coverageDraft.open = false
                    Toast.makeText(this@IncomeDistributionActivity, R.string.overspend_empty, Toast.LENGTH_LONG).show()
                    return@launch
                }
                val ids = candidates.map { it.id }.toSet()
                if (!restoreDraft) {
                    coverageDraft.selected.clear()
                    coverageDraft.raw.clear()
                    candidates.forEach { category ->
                        val existing = amounts[category.id] ?: 0.0
                        if (existing > 0.0) {
                            coverageDraft.selected.add(category.id)
                            coverageDraft.raw[category.id] = MoneyFormat.format(existing)
                        }
                    }
                }
                val other = amounts.filterKeys { it !in ids }.values.sum()
                OverspendCoverageDialog.show(this@IncomeDistributionActivity, candidates, parents,
                    totalIncome, other, coverageDraft, onClose = { updateTotals() }) { allocation ->
                    ids.forEach { id -> amounts.remove(id); selectedIds.remove(id); coverageIds.remove(id) }
                    allocation.forEach { (id, value) ->
                        selectedIds.add(id); amounts[id] = value; coverageIds.add(id)
                    }
                    findViewById<TextView>(R.id.distributionModeHint).setText(R.string.overspend_mode)
                    refreshList()
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled
            } catch (_: Exception) {
                coverageDraft.open = false
                Toast.makeText(this@IncomeDistributionActivity, R.string.overspend_refresh_error, Toast.LENGTH_LONG).show()
            } finally {
                coverageLoading = false
                updateTotals()
            }
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

    private fun applySavedTemplateIfEmpty() {
        if (selectedIds.isNotEmpty() || amounts.isNotEmpty()) return
        if (intent.hasExtra(EXTRA_PREFILL_CATEGORY_IDS)) return
        val budgetId = intent.getIntExtra(BudgetIntentExtras.BUDGET_ID, manager.getActiveBudgetId())
        val template = IncomeDistributionTemplatePreferences.load(this, budgetId) ?: return
        applyTemplate(template, showToast = false)
    }

    private fun applyLastTemplate(showToast: Boolean = true) {
        val budgetId = intent.getIntExtra(BudgetIntentExtras.BUDGET_ID, manager.getActiveBudgetId())
        val template = IncomeDistributionTemplatePreferences.load(this, budgetId)
        if (template == null) {
            if (showToast) {
                Toast.makeText(this, R.string.income_distribution_no_template, Toast.LENGTH_SHORT).show()
            }
            return
        }
        applyTemplate(template, showToast = showToast)
    }

    private fun applyTemplate(template: IncomeDistributionTemplatePreferences.Template, showToast: Boolean) {
        val scaled = IncomeDistributionTemplatePreferences.scaledAmounts(template, totalIncome)
        if (scaled.isEmpty()) {
            if (showToast) {
                Toast.makeText(this, R.string.income_distribution_no_template, Toast.LENGTH_SHORT).show()
            }
            return
        }
        selectedIds = scaled.keys.toMutableList()
        amounts.clear()
        coverageIds.clear()
        scaled.forEach { (id, amount) -> amounts[id] = amount }
        findViewById<TextView>(R.id.distributionModeHint).setText(R.string.income_distribution_mode_template)
        refreshList()
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
        coverageIds.clear()
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
        coverageIds.clear()
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
        coverageIds.clear()
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
                startingBalance = category.currentBalance.takeIf { id in coverageIds },
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
        if (coverageIds.isNotEmpty()) {
            val covered = amounts.filterKeys { it in coverageIds }.values.sum()
            totalText.text = getString(R.string.overspend_summary, MoneyFormat.formatRub(totalIncome),
                MoneyFormat.formatRub(distributed - covered), MoneyFormat.formatRub(covered), MoneyFormat.formatRub(remaining))
            totalText.setTextColor(ContextCompat.getColor(this, if (remaining < -0.01) R.color.expense_red else R.color.text_primary))
        } else if (totalIncome > 0.0 && remaining > 0.01) {
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
            !submitting && !coverageLoading && !coverageDraft.open &&
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
        ItemsDialogHelper.show(
            context = this,
            title = getString(R.string.income_distribution_remainder_title),
            message = getString(R.string.income_distribution_remainder_msg, MoneyFormat.format(leftover)),
            items = labels,
            negativeText = getString(android.R.string.cancel),
        ) { which ->
            val category = visible[which]
            amounts[category.id] = MoneyFormat.roundMoney((amounts[category.id] ?: 0.0) + leftover)
            refreshList()
        }
    }

    private fun submit() {
        if (submitting || coverageLoading || coverageDraft.open) return
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
        submitting = true
        submitButton.isEnabled = false
        lifecycleScope.launch {
          try {
            require(distributionBudgetId == manager.getActiveBudgetId()) { "Выбран другой бюджет" }
            val sourceId = plannedSourceId
            var credited = items.sumOf { it.second }
            if (sourceId != null) {
                val applied = IncomePlanExecution(manager.database).receive(sourceId, plannedMonth, items, note,
                    receiptRequest, intent.getStringExtra(EXTRA_REPLACE_GROUP))
                if (!applied) {
                    credited = manager.database.budgetDao().getTransactionsByGroupId(receiptRequest).sumOf { it.amount }
                    Toast.makeText(this@IncomeDistributionActivity, R.string.income_plan_changed, Toast.LENGTH_LONG).show()
                }
                manager.reloadCategoriesFromDatabase()
                BudgetWidgetProvider.updateAll(this@IncomeDistributionActivity)
            } else {
                manager.applyTransactionGroup(items, "income", note, requestId = receiptRequest)
                credited = manager.database.budgetDao().getTransactionsByGroupId(receiptRequest).sumOf { it.amount }
            }
            IncomeDistributionTemplatePreferences.save(
                this@IncomeDistributionActivity,
                manager.getActiveBudgetId(),
                selectedIds,
                amounts,
                totalIncome,
            )
            val leftover = MoneyFormat.roundMoney(totalIncome - credited)
            if (leftover > 0.01) {
                PendingDistributionPreferences.setPending(
                    this@IncomeDistributionActivity,
                    manager.getActiveBudgetId(),
                    leftover,
                    note,
                    plannedSourceId,
                    plannedMonth.toString(),
                )
            } else {
                PendingDistributionPreferences.clear(this@IncomeDistributionActivity)
            }
            Toast.makeText(this@IncomeDistributionActivity, R.string.income_distribution_done, Toast.LENGTH_SHORT).show()
            finish()
          } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled
          } catch (error: Exception) {
            Toast.makeText(this@IncomeDistributionActivity, getString(R.string.income_plan_error, error.message.orEmpty()), Toast.LENGTH_LONG).show()
          } finally {
            submitting = false
            updateTotals()
          }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        coverageDraft.save(outState)
        outState.putIntArray("coverage_applied_ids", coverageIds.toIntArray())
        outState.putBoolean("coverage_initial_opened", coverageOpenedInitially)
        outState.putString("receipt_request", receiptRequest)
        outState.putInt("plan_source", plannedSourceId ?: 0)
        outState.putString("plan_month", plannedMonth.toString())
        outState.putInt("distribution_budget", distributionBudgetId)
        outState.putDouble("distribution_total", totalIncome)
        outState.putIntArray("distribution_ids", selectedIds.toIntArray())
        outState.putDoubleArray("distribution_amounts", selectedIds.map { amounts[it] ?: 0.0 }.toDoubleArray())
        super.onSaveInstanceState(outState)
    }

    companion object {
        const val EXTRA_OPEN_COVERAGE = "open_overspend_coverage"
        const val EXTRA_RECEIPT_REQUEST = "receipt_request"
        const val EXTRA_REPLACE_GROUP = "replace_income_group"
        const val EXTRA_INCOME_NOTE = "income_note"
        const val EXTRA_TOTAL_INCOME = "total_income"
        const val EXTRA_PREFILL_CATEGORY_IDS = "prefill_category_ids"
        const val EXTRA_PREFILL_AMOUNTS = "prefill_amounts"
    }
}
