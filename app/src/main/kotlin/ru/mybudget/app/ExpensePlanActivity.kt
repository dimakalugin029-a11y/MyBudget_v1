package ru.mybudget.app

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.mybudget.app.data.MonthlyCategoryPlanEntity

class ExpensePlanActivity : AppCompatActivity() {
    data class ExpensePlanRowUi(
        val category: BudgetCategory,
        val parentName: String?,
        val included: Boolean,
        val plannedAmount: Double,
        val spent: Double,
    )

    private lateinit var manager: BudgetManager
    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyText: TextView
    private lateinit var addCategoriesButton: MaterialButton
    private lateinit var adapter: ExpensePlanAdapter
    private var selectedBudgetId = 1
    private var year = 0
    private var month = 0
    private var allRows: List<ExpensePlanRowUi> = emptyList()
    private var leafCategories: List<BudgetCategory> = emptyList()
    private var parentNames: Map<Int, String> = emptyMap()
    private var isEditableMonth = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_expense_plan)
        manager = BudgetManager.getInstance(this)
        ScreenHeaderHelper.setup(this, getString(R.string.expense_plan_title), getString(R.string.main_icon_expense_plan))
        selectedBudgetId = manager.getActiveBudgetId()
        val current = MonthlyPlanHelper.currentMonth()
        year = current.year
        month = current.month

        adapter = ExpensePlanAdapter(
            onPersist = { row, amount -> persistAmount(row, amount) },
            onPreview = { previewRows -> bindSummaryFromVisible(previewRows) },
            onRemove = { row -> confirmRemove(row) },
        )
        recyclerView = findViewById(R.id.expensePlanRecycler)
        emptyText = findViewById(R.id.expensePlanEmpty)
        addCategoriesButton = findViewById(R.id.expensePlanAddCategories)
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter
        recyclerView.setHasFixedSize(false)
        recyclerView.isFocusable = false
        recyclerView.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS

        findViewById<View>(R.id.transactionBudgetPicker).setOnClickListener {
            BudgetPicker.show(this, onSwitched = {
                selectedBudgetId = manager.getActiveBudgetId()
                bindBudgetName()
                loadRows()
            })
        }
        findViewById<View>(R.id.expensePlanPrevMonth).setOnClickListener {
            val shifted = MonthlyPlanHelper.shiftMonth(year, month, -1)
            year = shifted.year
            month = shifted.month
            loadRows()
        }
        findViewById<View>(R.id.expensePlanNextMonth).setOnClickListener {
            val shifted = MonthlyPlanHelper.shiftMonth(year, month, 1)
            if (MonthlyPlanHelper.isFutureMonth(shifted.year, shifted.month)) return@setOnClickListener
            year = shifted.year
            month = shifted.month
            loadRows()
        }
        findViewById<View>(R.id.expensePlanFillDefaults).setOnClickListener { fillFromDefaults() }
        findViewById<View>(R.id.expensePlanSaveDefaults).setOnClickListener { saveAsDefaults() }
        addCategoriesButton.setOnClickListener { pickCategories() }
        emptyText.setOnClickListener {
            if (isEditableMonth && allRows.isNotEmpty()) pickCategories()
        }
        bindBudgetName()
        loadRows()
    }

    override fun onResume() {
        super.onResume()
        selectedBudgetId = manager.getActiveBudgetId()
        bindBudgetName()
        if (!adapter.hasFocusedAmountInput()) {
            loadRows()
        }
    }

    private fun bindBudgetName() {
        lifecycleScope.launch {
            val profiles = manager.getBudgetProfilesAsync()
            val name = profiles.firstOrNull { it.id == selectedBudgetId }?.name
                ?: getString(R.string.budget_profiles_default_name)
            findViewById<TextView>(R.id.transactionBudgetNameText).text = name
        }
    }

    private fun loadRows() {
        lifecycleScope.launch {
            manager.getCategoriesAsync()
            val built = withContext(Dispatchers.IO) { buildRows() }
            parentNames = withContext(Dispatchers.IO) {
                manager.getRootCategories(selectedBudgetId).associate { it.id to it.name }
            }
            leafCategories = built.map { it.category }
            allRows = built
            isEditableMonth = !MonthlyPlanHelper.isFutureMonth(year, month)
            val now = MonthlyPlanHelper.currentMonth()
            val isCurrentMonth = year == now.year && month == now.month
            findViewById<TextView>(R.id.expensePlanMonthLabel).text =
                MonthlyPlanHelper.formatMonthLabel(year, month)
            val next = MonthlyPlanHelper.shiftMonth(year, month, 1)
            findViewById<View>(R.id.expensePlanNextMonth).isEnabled =
                !MonthlyPlanHelper.isFutureMonth(next.year, next.month)
            refreshVisibleList(isCurrentMonth)
            if (isCurrentMonth && isEditableMonth) {
                val noPlan = built.none { it.included && it.plannedAmount > 0.0 }
                val hasDefaults = built.any { it.category.defaultPlannedAmount > 0.0 }
                if (noPlan && hasDefaults && built.none { it.included }) {
                    Toast.makeText(this@ExpensePlanActivity, R.string.expense_plan_fill_defaults_hint, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private suspend fun buildRows(): List<ExpensePlanRowUi> {
        val parents = manager.getRootCategories(selectedBudgetId).associate { it.id to it.name }
        val leaves = manager.getCategoriesForBudget(selectedBudgetId)
            .filter { !manager.hasSubcategories(it.id) }
            .sortedWith(compareBy({ parents[it.parentId].orEmpty() }, { it.name }))
        val plans = manager.repository.getMonthlyPlansForBudgetMonth(selectedBudgetId, year, month)
            .associateBy { it.categoryId }
        val (startMs, endMs) = MonthlyPlanHelper.monthRangeMs(year, month)
        return leaves.map { category ->
            val plan = plans[category.id]
            val included = plan?.isEnabled == true
            val planned = if (included) plan?.plannedAmount ?: 0.0 else 0.0
            val spent = manager.repository.getExpenseSumForCategoryInRange(category.id, startMs, endMs)
            ExpensePlanRowUi(
                category = category,
                parentName = parents[category.parentId],
                included = included,
                plannedAmount = planned,
                spent = spent,
            )
        }
    }

    private fun visibleRows(): List<ExpensePlanRowUi> = allRows.filter { it.included }

    private fun refreshVisibleList(isCurrentMonth: Boolean = run {
        val now = MonthlyPlanHelper.currentMonth()
        year == now.year && month == now.month
    }) {
        val visible = visibleRows()
        adapter.submit(visible, isEditableMonth)
        val noCategories = allRows.isEmpty()
        val noneSelected = visible.isEmpty()
        emptyText.visibility = if (noCategories || noneSelected) View.VISIBLE else View.GONE
        emptyText.text = when {
            noCategories -> getString(R.string.expense_plan_empty)
            noneSelected -> getString(R.string.expense_plan_no_selected)
            else -> getString(R.string.expense_plan_no_selected)
        }
        recyclerView.visibility = if (noneSelected) View.GONE else View.VISIBLE
        addCategoriesButton.visibility =
            if (isEditableMonth && !noCategories) View.VISIBLE else View.GONE
        findViewById<View>(R.id.expensePlanActions).visibility =
            if (isEditableMonth && !noCategories) View.VISIBLE else View.GONE
        findViewById<View>(R.id.expensePlanSelectHint).visibility =
            if (isEditableMonth && !noCategories) View.VISIBLE else View.GONE
        bindSummary(allRows, isCurrentMonth)
    }

    private fun bindSummaryFromVisible(previewVisible: List<ExpensePlanRowUi>) {
        val now = MonthlyPlanHelper.currentMonth()
        val isCurrentMonth = year == now.year && month == now.month
        val merged = allRows.map { row ->
            previewVisible.firstOrNull { it.category.id == row.category.id } ?: row
        }
        bindSummary(merged, isCurrentMonth)
    }

    private fun bindSummary(built: List<ExpensePlanRowUi>, isCurrentMonth: Boolean) {
        val plannedRows = built.filter { it.included && it.plannedAmount > 0.0 }
        val summary = findViewById<TextView>(R.id.expensePlanSummaryLine)
        if (plannedRows.isEmpty()) {
            summary.setText(R.string.expense_plan_summary_no_plan)
            summary.setTextColor(ContextCompat.getColor(this, R.color.main_hero_text_primary))
            return
        }
        val totalPlan = plannedRows.sumOf { it.plannedAmount }
        val totalSpent = plannedRows.sumOf { it.spent }
        val diff = totalPlan - totalSpent
        val diffLabel = if (diff >= 0.0) {
            getString(R.string.expense_plan_summary_saved, MoneyFormat.formatRub(diff))
        } else {
            getString(R.string.expense_plan_summary_overspend, MoneyFormat.formatRub(-diff))
        }
        summary.text = getString(
            R.string.expense_plan_summary_compact,
            MoneyFormat.formatRub(totalPlan),
            MoneyFormat.formatRub(totalSpent),
            diffLabel,
        )
        summary.setTextColor(
            ContextCompat.getColor(this, if (diff >= 0.0) R.color.main_hero_text_primary else R.color.expense_red),
        )
        if (!isCurrentMonth) {
            summary.text = getString(R.string.expense_plan_past_month_note, summary.text)
            summary.setTextColor(ContextCompat.getColor(this, R.color.main_hero_text_primary))
        }
    }

    private fun pickCategories() {
        if (!isEditableMonth || leafCategories.isEmpty()) return
        CategoryMultiPicker.show(
            activity = this,
            leaves = leafCategories,
            parents = parentNames,
            alreadySelected = allRows.filter { it.included }.map { it.category.id }.toSet(),
            titleRes = R.string.expense_plan_pick_categories_title,
            defaultForSelectAll = { it.defaultPlannedAmount > 0.0 },
        ) { ids -> addCategoriesToPlan(ids) }
    }

    private fun addCategoriesToPlan(categoryIds: List<Int>) {
        if (!isEditableMonth || categoryIds.isEmpty()) return
        lifecycleScope.launch(Dispatchers.IO) {
            categoryIds.forEach { categoryId ->
                val row = allRows.firstOrNull { it.category.id == categoryId } ?: return@forEach
                val amount = MonthlyPlanHelper.suggestedAmount(row.category, null)
                manager.repository.upsertMonthlyPlan(
                    MonthlyCategoryPlanEntity(
                        year = year,
                        month = month,
                        categoryId = categoryId,
                        budgetId = selectedBudgetId,
                        plannedAmount = amount,
                        isEnabled = true,
                    ),
                )
            }
            withContext(Dispatchers.Main) { loadRows() }
        }
    }

    private fun confirmRemove(row: ExpensePlanRowUi) {
        if (!isEditableMonth) return
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.expense_plan_remove_confirm, row.category.name))
            .setPositiveButton(R.string.expense_plan_remove) { _, _ -> removeFromPlan(row) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun removeFromPlan(row: ExpensePlanRowUi) {
        persistPlanState(row, included = false, amount = 0.0)
    }

    private fun persistAmount(row: ExpensePlanRowUi, amount: Double) {
        if (!isEditableMonth || isFinishing || isDestroyed) return
        val rounded = MoneyFormat.roundMoney(amount)
        val updated = row.copy(included = true, plannedAmount = rounded)
        val index = allRows.indexOfFirst { it.category.id == row.category.id }
        if (index < 0) return
        allRows = allRows.toMutableList().also { it[index] = updated }
        val visibleIndex = visibleRows().indexOfFirst { it.category.id == row.category.id }
        if (visibleIndex >= 0) {
            adapter.syncVisibleRow(visibleIndex, updated)
        }
        val now = MonthlyPlanHelper.currentMonth()
        bindSummary(allRows, year == now.year && month == now.month)
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching {
                manager.repository.upsertMonthlyPlan(
                    MonthlyCategoryPlanEntity(
                        year = year,
                        month = month,
                        categoryId = row.category.id,
                        budgetId = selectedBudgetId,
                        plannedAmount = rounded,
                        isEnabled = true,
                    ),
                )
            }
        }
    }

    private fun persistPlanState(row: ExpensePlanRowUi, included: Boolean, amount: Double) {
        if (!isEditableMonth || isFinishing || isDestroyed) return
        val rounded = MoneyFormat.roundMoney(amount)
        val updated = row.copy(
            included = included,
            plannedAmount = if (included) rounded else 0.0,
        )
        val index = allRows.indexOfFirst { it.category.id == row.category.id }
        if (index < 0) return
        allRows = allRows.toMutableList().also { it[index] = updated }
        refreshVisibleList()
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching {
                manager.repository.upsertMonthlyPlan(
                    MonthlyCategoryPlanEntity(
                        year = year,
                        month = month,
                        categoryId = row.category.id,
                        budgetId = selectedBudgetId,
                        plannedAmount = if (included) rounded else 0.0,
                        isEnabled = included,
                    ),
                )
            }
        }
    }

    private fun fillFromDefaults() {
        lifecycleScope.launch(Dispatchers.IO) {
            allRows.filter { it.category.defaultPlannedAmount > 0.0 }.forEach { row ->
                manager.repository.upsertMonthlyPlan(
                    MonthlyCategoryPlanEntity(
                        year = year,
                        month = month,
                        categoryId = row.category.id,
                        budgetId = selectedBudgetId,
                        plannedAmount = row.category.defaultPlannedAmount,
                        isEnabled = true,
                    ),
                )
            }
            withContext(Dispatchers.Main) {
                Toast.makeText(this@ExpensePlanActivity, R.string.expense_plan_defaults_applied, Toast.LENGTH_SHORT).show()
                loadRows()
            }
        }
    }

    private fun saveAsDefaults() {
        lifecycleScope.launch {
            allRows.filter { it.included && it.plannedAmount > 0.0 }.forEach { row ->
                manager.updateDefaultPlannedAmount(row.category.id, row.plannedAmount)
            }
            Toast.makeText(this@ExpensePlanActivity, R.string.expense_plan_defaults_saved, Toast.LENGTH_SHORT).show()
        }
    }

    private class ExpensePlanAdapter(
        private val onPersist: (ExpensePlanRowUi, Double) -> Unit,
        private val onPreview: (List<ExpensePlanRowUi>) -> Unit,
        private val onRemove: (ExpensePlanRowUi) -> Unit,
    ) : RecyclerView.Adapter<ExpensePlanAdapter.Holder>() {
        private var rows: List<ExpensePlanRowUi> = emptyList()
        private var editable = true

        fun submit(data: List<ExpensePlanRowUi>, isEditable: Boolean) {
            rows = data
            editable = isEditable
            notifyDataSetChanged()
        }

        fun syncVisibleRow(index: Int, row: ExpensePlanRowUi) {
            if (index !in rows.indices) return
            rows = rows.toMutableList().also { it[index] = row }
            val holder = recyclerView?.findViewHolderForAdapterPosition(index) as? Holder ?: return
            if (holder.amountInput.hasFocus()) {
                holder.bindRowState(row, editable)
                return
            }
            holder.suppressEvents = true
            holder.amountInput.setText(
                if (row.plannedAmount > 0.0) MoneyFormat.format(row.plannedAmount) else "",
            )
            holder.suppressEvents = false
            holder.bindRowState(row, editable)
        }

        fun hasFocusedAmountInput(): Boolean {
            val focused = recyclerView?.findFocus() ?: return false
            return focused.id == R.id.expensePlanAmountInput
        }

        private var recyclerView: RecyclerView? = null

        override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
            super.onAttachedToRecyclerView(recyclerView)
            this.recyclerView = recyclerView
        }

        override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
            super.onDetachedFromRecyclerView(recyclerView)
            if (this.recyclerView === recyclerView) {
                this.recyclerView = null
            }
        }

        class Holder(v: View) : RecyclerView.ViewHolder(v) {
            val name: TextView = v.findViewById(R.id.expensePlanCategoryName)
            val parent: TextView = v.findViewById(R.id.expensePlanParentName)
            val amountInput: EditText = v.findViewById(R.id.expensePlanAmountInput)
            val spent: TextView = v.findViewById(R.id.expensePlanSpentText)
            val diff: TextView = v.findViewById(R.id.expensePlanDiffText)
            val removeButton: MaterialButton = v.findViewById(R.id.expensePlanRemoveButton)
            var suppressEvents = false
            var amountWatcher: TextWatcher? = null

            fun clearListeners() {
                amountInput.onFocusChangeListener = null
                amountWatcher?.let { amountInput.removeTextChangedListener(it) }
                amountWatcher = null
                removeButton.setOnClickListener(null)
            }

            fun bindRowState(row: ExpensePlanRowUi, editable: Boolean) {
                val ctx = itemView.context
                bindDiff(ctx, row)
                amountInput.isEnabled = editable
                removeButton.visibility = if (editable) View.VISIBLE else View.GONE
            }

            fun bindDiff(ctx: android.content.Context, row: ExpensePlanRowUi) {
                spent.text = ctx.getString(R.string.expense_plan_spent_row, MoneyFormat.formatRub(row.spent))
                val diffValue = row.plannedAmount - row.spent
                if (row.plannedAmount > 0.0) {
                    diff.visibility = View.VISIBLE
                    diff.text = if (diffValue >= 0.0) {
                        ctx.getString(R.string.expense_plan_diff_saved, MoneyFormat.formatRub(diffValue))
                    } else {
                        ctx.getString(R.string.expense_plan_diff_over, MoneyFormat.formatRub(-diffValue))
                    }
                    diff.setTextColor(
                        ContextCompat.getColor(ctx, if (diffValue >= 0.0) R.color.income_green else R.color.expense_red),
                    )
                } else {
                    diff.visibility = View.GONE
                }
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_expense_plan_row, parent, false)
            return Holder(v)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val row = rows[position]
            holder.clearListeners()
            holder.name.text = row.category.name
            if (row.parentName.isNullOrBlank()) {
                holder.parent.visibility = View.GONE
            } else {
                holder.parent.visibility = View.VISIBLE
                holder.parent.text = row.parentName
            }
            holder.suppressEvents = true
            holder.amountInput.isEnabled = editable
            if (!holder.amountInput.hasFocus()) {
                holder.amountInput.setText(
                    if (row.plannedAmount > 0.0) MoneyFormat.format(row.plannedAmount) else "",
                )
            }
            holder.suppressEvents = false
            holder.bindRowState(row, editable)

            holder.amountInput.setOnFocusChangeListener { _, hasFocus ->
                if (holder.suppressEvents || !editable) return@setOnFocusChangeListener
                if (!hasFocus) {
                    recyclerView?.post { persistFromHolder(holder) }
                }
            }

            val watcher = object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    if (holder.suppressEvents || !editable) return
                    val pos = holder.bindingAdapterPosition
                    if (pos == RecyclerView.NO_POSITION || pos !in rows.indices) return
                    val amount = MoneyFormat.parse(s) ?: 0.0
                    val preview = rows[pos].copy(plannedAmount = MoneyFormat.roundMoney(amount))
                    rows = rows.toMutableList().also { it[pos] = preview }
                    holder.bindRowState(preview, editable)
                    onPreview(rows)
                }
            }
            holder.amountWatcher = watcher
            holder.amountInput.addTextChangedListener(watcher)

            holder.removeButton.setOnClickListener {
                if (editable) onRemove(row)
            }
        }

        override fun onViewRecycled(holder: Holder) {
            holder.clearListeners()
            super.onViewRecycled(holder)
        }

        private fun persistFromHolder(holder: Holder) {
            val pos = holder.bindingAdapterPosition
            if (pos == RecyclerView.NO_POSITION || pos !in rows.indices) return
            val amount = MoneyFormat.parse(holder.amountInput.text) ?: 0.0
            onPersist(rows[pos], amount)
        }

        override fun getItemCount(): Int = rows.size
    }
}
