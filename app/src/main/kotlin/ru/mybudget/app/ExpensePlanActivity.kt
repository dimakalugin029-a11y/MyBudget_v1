package ru.mybudget.app

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.mybudget.app.data.MonthlyCategoryPlanEntity
import ru.mybudget.app.setup.OverspendPreferences

class ExpensePlanActivity : AppCompatActivity() {
    data class ExpensePlanRowUi(
        val category: BudgetCategory,
        val parentName: String?,
        val included: Boolean,
        val plannedAmount: Double,
        val spent: Double,
    )

    private lateinit var manager: BudgetManager
    private lateinit var adapter: ExpensePlanAdapter
    private var selectedBudgetId = 1
    private var year = 0
    private var month = 0
    private var rows: List<ExpensePlanRowUi> = emptyList()
    private var isEditableMonth = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_expense_plan)
        manager = BudgetManager.getInstance(this)
        ScreenHeaderHelper.setup(this, getString(R.string.expense_plan_title))
        selectedBudgetId = manager.getActiveBudgetId()
        val current = MonthlyPlanHelper.currentMonth()
        year = current.year
        month = current.month

        adapter = ExpensePlanAdapter { row, included, amount -> saveRow(row, included, amount) }
        findViewById<RecyclerView>(R.id.expensePlanRecycler).apply {
            layoutManager = LinearLayoutManager(this@ExpensePlanActivity)
            this.adapter = this@ExpensePlanActivity.adapter
        }
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
        setupOverspendSettings()
        bindBudgetName()
        loadRows()
    }

    override fun onResume() {
        super.onResume()
        selectedBudgetId = manager.getActiveBudgetId()
        bindBudgetName()
        loadRows()
    }

    private fun bindBudgetName() {
        lifecycleScope.launch {
            val profiles = manager.getBudgetProfilesAsync()
            val name = profiles.firstOrNull { it.id == selectedBudgetId }?.name
                ?: getString(R.string.budget_profiles_default_name)
            findViewById<TextView>(R.id.transactionBudgetNameText).text = name
        }
    }

    private fun setupOverspendSettings() {
        val overspendSwitch = findViewById<SwitchCompat>(R.id.expensePlanOverspendSwitch)
        val thresholdInput = findViewById<EditText>(R.id.expensePlanThresholdInput)
        overspendSwitch.isChecked = OverspendPreferences.isEnabled(this)
        thresholdInput.setText(OverspendPreferences.getThresholdPercent(this).toString())
        overspendSwitch.setOnCheckedChangeListener { _, checked ->
            OverspendPreferences.setEnabled(this, checked)
        }
        thresholdInput.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) return@setOnFocusChangeListener
            val value = thresholdInput.text.toString().toIntOrNull() ?: 100
            OverspendPreferences.setThresholdPercent(this, value)
            thresholdInput.setText(OverspendPreferences.getThresholdPercent(this).toString())
        }
    }

    private fun loadRows() {
        lifecycleScope.launch {
            manager.getCategoriesAsync()
            val built = withContext(Dispatchers.IO) {
                val parents = manager.getRootCategories(selectedBudgetId).associate { it.id to it.name }
                val leaves = manager.getCategoriesForBudget(selectedBudgetId)
                    .filter { !manager.hasSubcategories(it.id) }
                    .sortedWith(compareBy({ parents[it.parentId].orEmpty() }, { it.name }))
                val plans = manager.repository.getMonthlyPlansForBudgetMonth(selectedBudgetId, year, month)
                    .associateBy { it.categoryId }
                val (startMs, endMs) = MonthlyPlanHelper.monthRangeMs(year, month)
                leaves.map { category ->
                    val plan = plans[category.id]
                    val included = MonthlyPlanHelper.isIncludedInPlan(category, plan)
                    val planned = if (plan != null && plan.isEnabled) plan.plannedAmount else 0.0
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
            rows = built
            isEditableMonth = !MonthlyPlanHelper.isFutureMonth(year, month)
            val now = MonthlyPlanHelper.currentMonth()
            val isCurrentMonth = year == now.year && month == now.month
            findViewById<TextView>(R.id.expensePlanMonthLabel).text =
                MonthlyPlanHelper.formatMonthLabel(year, month)
            val next = MonthlyPlanHelper.shiftMonth(year, month, 1)
            findViewById<View>(R.id.expensePlanNextMonth).isEnabled =
                !MonthlyPlanHelper.isFutureMonth(next.year, next.month)
            adapter.submit(built, isEditableMonth)
            val empty = built.isEmpty()
            findViewById<View>(R.id.expensePlanEmpty).visibility = if (empty) View.VISIBLE else View.GONE
            findViewById<View>(R.id.expensePlanRecycler).visibility = if (empty) View.GONE else View.VISIBLE
            findViewById<View>(R.id.expensePlanActions).visibility =
                if (isEditableMonth && !empty) View.VISIBLE else View.GONE
            bindSummary(built, isCurrentMonth)
            if (isCurrentMonth) {
                val noPlan = built.none { it.included && it.plannedAmount > 0.0 }
                val hasDefaults = built.any { it.category.defaultPlannedAmount > 0.0 }
                if (noPlan && hasDefaults) {
                    Toast.makeText(this@ExpensePlanActivity, R.string.expense_plan_fill_defaults_hint, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun bindSummary(built: List<ExpensePlanRowUi>, isCurrentMonth: Boolean) {
        val plannedRows = built.filter { it.included && it.plannedAmount > 0.0 }
        val summary = findViewById<TextView>(R.id.expensePlanSummaryLine)
        if (plannedRows.isEmpty()) {
            summary.setText(R.string.expense_plan_summary_no_plan)
            summary.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
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
            ContextCompat.getColor(this, if (diff >= 0.0) R.color.income_green else R.color.expense_red),
        )
        if (!isCurrentMonth) {
            summary.text = getString(R.string.expense_plan_past_month_note, summary.text)
            summary.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
        }
    }

    private fun saveRow(row: ExpensePlanRowUi, included: Boolean, amount: Double) {
        if (!isEditableMonth) return
        val rounded = MoneyFormat.roundMoney(amount)
        lifecycleScope.launch(Dispatchers.IO) {
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
            withContext(Dispatchers.Main) { loadRows() }
        }
    }

    private fun fillFromDefaults() {
        lifecycleScope.launch(Dispatchers.IO) {
            rows.filter { it.category.defaultPlannedAmount > 0.0 }.forEach { row ->
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
            rows.filter { it.included && it.plannedAmount > 0.0 }.forEach { row ->
                manager.updateDefaultPlannedAmount(row.category.id, row.plannedAmount)
            }
            Toast.makeText(this@ExpensePlanActivity, R.string.expense_plan_defaults_saved, Toast.LENGTH_SHORT).show()
        }
    }

    private class ExpensePlanAdapter(
        private val onRowChanged: (ExpensePlanRowUi, Boolean, Double) -> Unit,
    ) : RecyclerView.Adapter<ExpensePlanAdapter.Holder>() {
        private var rows: List<ExpensePlanRowUi> = emptyList()
        private var editable = true

        fun submit(data: List<ExpensePlanRowUi>, isEditable: Boolean) {
            rows = data
            editable = isEditable
            notifyDataSetChanged()
        }

        class Holder(v: View) : RecyclerView.ViewHolder(v) {
            val includeCheck: CheckBox = v.findViewById(R.id.expensePlanIncludeCheck)
            val name: TextView = v.findViewById(R.id.expensePlanCategoryName)
            val parent: TextView = v.findViewById(R.id.expensePlanParentName)
            val amountInput: EditText = v.findViewById(R.id.expensePlanAmountInput)
            val spent: TextView = v.findViewById(R.id.expensePlanSpentText)
            val diff: TextView = v.findViewById(R.id.expensePlanDiffText)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_expense_plan_row, parent, false)
            return Holder(v)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val row = rows[position]
            val ctx = holder.itemView.context
            holder.name.text = row.category.name
            if (row.parentName.isNullOrBlank()) {
                holder.parent.visibility = View.GONE
            } else {
                holder.parent.visibility = View.VISIBLE
                holder.parent.text = row.parentName
            }
            holder.includeCheck.setOnCheckedChangeListener(null)
            holder.includeCheck.isChecked = row.included
            holder.includeCheck.isEnabled = editable
            holder.amountInput.isEnabled = editable && row.included
            holder.amountInput.setText(
                if (row.plannedAmount > 0.0) MoneyFormat.format(row.plannedAmount) else "",
            )
            holder.spent.text = ctx.getString(R.string.expense_plan_spent_row, MoneyFormat.formatRub(row.spent))
            val diff = row.plannedAmount - row.spent
            if (row.included && row.plannedAmount > 0.0) {
                holder.diff.visibility = View.VISIBLE
                holder.diff.text = if (diff >= 0.0) {
                    ctx.getString(R.string.expense_plan_diff_saved, MoneyFormat.formatRub(diff))
                } else {
                    ctx.getString(R.string.expense_plan_diff_over, MoneyFormat.formatRub(-diff))
                }
                holder.diff.setTextColor(
                    ContextCompat.getColor(ctx, if (diff >= 0.0) R.color.income_green else R.color.expense_red),
                )
            } else {
                holder.diff.visibility = View.GONE
            }
            holder.includeCheck.setOnCheckedChangeListener { _: CompoundButton, checked: Boolean ->
                holder.amountInput.isEnabled = editable && checked
                if (checked && holder.amountInput.text.isNullOrBlank()) {
                    val suggested = MonthlyPlanHelper.suggestedAmount(row.category, null)
                    if (suggested > 0.0) holder.amountInput.setText(MoneyFormat.format(suggested))
                }
                commitRow(holder, checked)
            }
            holder.amountInput.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus && editable && !holder.includeCheck.isChecked) {
                    holder.includeCheck.isChecked = true
                    holder.amountInput.isEnabled = true
                } else if (!hasFocus && editable) {
                    commitRow(holder, holder.includeCheck.isChecked)
                }
            }
            holder.name.setOnClickListener {
                if (editable) holder.includeCheck.isChecked = !holder.includeCheck.isChecked
            }
        }

        private fun commitRow(holder: Holder, included: Boolean) {
            val pos = holder.bindingAdapterPosition
            if (pos == RecyclerView.NO_POSITION) return
            val amount = MoneyFormat.parse(holder.amountInput.text) ?: 0.0
            onRowChanged(rows[pos], included, amount)
        }

        override fun getItemCount(): Int = rows.size
    }
}
