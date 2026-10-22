package ru.mybudget.app

import android.os.Bundle
import androidx.room.withTransaction
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
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
import ru.mybudget.app.data.MonthlyIncomePlanEntity
import ru.mybudget.app.data.PlannedIncomeSourceEntity
import java.util.Locale

class YearBudgetingActivity : AppCompatActivity() {
    private lateinit var manager: BudgetManager
    private lateinit var adapter: MonthAdapter
    private var budgetId: Int = 1
    private var year: Int = MonthlyPlanHelper.currentMonth().year
    private var loaded: YearBudgetingHelper.YearPlan? = null
    private var editorTemplates: EditorTemplates? = null

    private data class EditorTemplates(
        val sources: List<PlannedIncomeSourceEntity>,
        val expenseCategories: List<BudgetCategory>,
        val parentNames: Map<Int, String>,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_year_budgeting)
        manager = BudgetManager.getInstance(this)
        budgetId = manager.getActiveBudgetId()
        ScreenHeaderHelper.setup(this, getString(R.string.year_budget_title), getString(R.string.main_icon_expense_plan))
        adapter = MonthAdapter(
            onToggle = { position -> adapter.toggle(position) },
            onEdit = { position -> showMonthEditor(position) },
        )
        findViewById<RecyclerView>(R.id.yearBudgetList).apply {
            layoutManager = LinearLayoutManager(this@YearBudgetingActivity)
            adapter = this@YearBudgetingActivity.adapter
        }
        findViewById<MaterialButton>(R.id.yearBudgetCategoryToggle).setOnClickListener { toggleCategorySummary() }
        findViewById<View>(R.id.yearBudgetPrevYear).setOnClickListener {
            year -= 1
            load()
        }
        findViewById<View>(R.id.yearBudgetNextYear).setOnClickListener {
            year += 1
            load()
        }
        load()
    }

    private var loadJob: kotlinx.coroutines.Job? = null
    private fun load() {
        loadJob?.cancel()
        findViewById<TextView>(R.id.yearBudgetYearLabel).text = year.toString()
        loadJob = lifecycleScope.launch {
            val plan = withContext(Dispatchers.IO) { buildYearPlan() }
            loaded = plan
            adapter.submit(plan)
            bindSummary(plan)
            bindCategorySummary(plan)
        }
    }

    private fun toggleCategorySummary() {
        val card = findViewById<View>(R.id.yearBudgetCategorySummaryCard)
        val toggle = findViewById<MaterialButton>(R.id.yearBudgetCategoryToggle)
        val show = card.visibility != View.VISIBLE
        card.visibility = if (show) View.VISIBLE else View.GONE
        toggle.text = getString(
            if (show) R.string.year_budget_category_toggle_hide else R.string.year_budget_category_toggle_show,
        )
    }

    private suspend fun buildYearPlan(): YearBudgetingHelper.YearPlan {
        val categories = manager.database.budgetDao().getAllCategoriesForExport().filter { it.budgetId == budgetId }.map { BudgetCategory.fromEntity(it) }
        val expenseCategories = categories
            .filter { category -> categories.none { it.parentId == category.id } }
            .sortedWith(compareBy({ it.name }))
        val categoryIds = categories.map { it.id }.toSet()
        val sources = manager.repository.getPlannedIncomeSourcesByBudgetOnce(budgetId)
        val obligations = manager.repository.getPlannedObligationsByBudgetOnce(budgetId)
        val allCategoryPlans = manager.repository.getMonthlyPlansForBudget(budgetId)
            .filter { it.year == year }
            .groupBy { it.month }
        val incomeOverrides = manager.repository.getMonthlyIncomePlansForBudget(budgetId)
            .filter { it.year == year }
            .groupBy { it.month }
        val now = MonthlyPlanHelper.currentMonth()
        val plansByMonth = mutableMapOf<Int, List<MonthlyCategoryPlanEntity>>()
        for (month in 1..12) {
            val plans = allCategoryPlans[month] ?: emptyList()
            plansByMonth[month] = plans
        }
        val factByMonth = mutableMapOf<Int, YearBudgetingHelper.MonthFact>()
        for (month in 1..12) {
            if (!YearBudgetingHelper.isPastOrCurrentMonth(year, month, now.year, now.month)) continue
            val (fromMs, toMs) = MonthBudgetComparisonHelper.monthRangeMs(year, month)
            val transactions = manager.repository.getTransactionsInRange(fromMs, toMs)
                .filter { it.categoryId in categoryIds }
            factByMonth[month] = YearBudgetingHelper.factTotals(transactions)
        }
        val yearRange = MonthBudgetComparisonHelper.monthRangeMs(year, 1)
        val yearEnd = MonthBudgetComparisonHelper.monthRangeMs(year, 12).second
        val factByCategory = manager.repository.getTransactionsInRange(yearRange.first, yearEnd)
            .filter { it.type == "expense" && it.categoryId in categoryIds }
            .groupBy { it.categoryId }
            .mapValues { entry -> MoneyFormat.roundMoney(entry.value.sumOf { it.amount }) }
        return YearBudgetingHelper.buildYear(
            year = year,
            sources = sources,
            expenseCategories = expenseCategories,
            categoryPlansByMonth = plansByMonth,
            incomeOverridesByMonth = incomeOverrides,
            obligations = obligations,
            factByMonth = factByMonth,
            factByCategory = factByCategory,
            preserveUnknownHistory = true,
        )
    }

    private fun bindSummary(plan: YearBudgetingHelper.YearPlan) {
        val sb = StringBuilder()
        plan.quarters.forEach { q ->
            sb.append(
                getString(
                    R.string.year_budget_quarter_line,
                    q.quarter,
                    MoneyFormat.formatRub(q.incomeTotal),
                    MoneyFormat.formatRub(q.expenseTotal),
                    MoneyFormat.formatRub(q.net),
                ),
            ).append('\n')
        }
        sb.append(
            getString(
                R.string.year_budget_year_line,
                MoneyFormat.formatRub(plan.incomeTotal),
                MoneyFormat.formatRub(plan.expenseTotal),
                MoneyFormat.formatRub(plan.net),
            ),
        )
        if (plan.months.any { !it.incomePlanKnown || !it.expensePlanKnown }) sb.append("\nИтоги плана неполные: для части прошлых месяцев план не был сохранён.")
        findViewById<TextView>(R.id.yearBudgetSummary).text = sb.toString()
        findViewById<View>(R.id.yearBudgetEmpty).visibility =
            if (plan.months.all { it.incomeLines.isEmpty() && it.expenseLines.isEmpty() }) View.VISIBLE else View.GONE
    }

    private fun bindCategorySummary(plan: YearBudgetingHelper.YearPlan) {
        lifecycleScope.launch {
            val nameById = withContext(Dispatchers.IO) {
                val parents = manager.getRootCategories(budgetId).associate { it.id to it.name }
                manager.getCategoriesForBudget(budgetId).associate { it.id to it.name }
            }
            val summaries = YearBudgetingHelper.categoryYearSummaries(
                months = plan.months,
                factByCategory = plan.factByCategory,
                nameById = nameById,
            )
            val view = findViewById<TextView>(R.id.yearBudgetCategorySummary)
            if (summaries.isEmpty()) {
                view.text = getString(R.string.year_budget_category_summary_empty)
                return@launch
            }
            val sb = StringBuilder()
            summaries.forEach { s ->
                sb.append(
                    getString(
                        R.string.year_budget_category_line,
                        s.name,
                        MoneyFormat.formatRub(s.planTotal),
                        MoneyFormat.formatRub(s.factTotal),
                        MoneyFormat.formatRub(s.remaining),
                    ),
                ).append('\n')
            }
            val totalsLine = getString(
                R.string.year_budget_category_totals,
                MoneyFormat.formatRub(summaries.sumOf { it.planTotal }),
                MoneyFormat.formatRub(summaries.sumOf { it.factTotal }),
                MoneyFormat.formatRub(summaries.sumOf { it.remaining }),
            )
            view.text = sb.append(totalsLine).toString()
        }
    }

    private fun showMonthEditor(position: Int) {
        val monthPlan = loaded?.months?.getOrNull(position) ?: return
        lifecycleScope.launch {
            val templates = editorTemplates ?: withContext(Dispatchers.IO) {
                val categories = manager.database.budgetDao().getAllCategoriesForExport().filter { it.budgetId == budgetId }.map { BudgetCategory.fromEntity(it) }
                    .filter { it.isActive && !manager.hasSubcategories(it.id) }
                    .sortedBy { it.name }
                val parents = manager.getRootCategories(budgetId).associate { it.id to it.name }
                EditorTemplates(
                    sources = manager.repository.getPlannedIncomeSourcesByBudgetOnce(budgetId)
                        .filter { it.isActive },
                    expenseCategories = categories,
                    parentNames = parents,
                )
            }.also { editorTemplates = it }
            showEditorDialog(monthPlan, templates)
        }
    }

    private fun showEditorDialog(
        monthPlan: YearBudgetingHelper.MonthPlan,
        templates: EditorTemplates,
    ) {
        val title = getString(
            R.string.year_budget_editor_title,
            MonthBudgetComparisonHelper.monthLabel(monthPlan.year, monthPlan.month),
        )
        val scroll = ScrollView(this)
        scroll.setBackgroundColor(ContextCompat.getColor(this, R.color.md_theme_surface))
        val container = LinearLayout(this)
        container.orientation = LinearLayout.VERTICAL
        container.setPadding(
            resources.getDimensionPixelSize(R.dimen.space_16),
            resources.getDimensionPixelSize(R.dimen.space_8),
            resources.getDimensionPixelSize(R.dimen.space_16),
            0,
        )
        scroll.addView(
            container,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )

        val incomeInputs = mutableMapOf<Int, EditText>()
        val expenseInputs = mutableMapOf<Int, EditText>()

        addSectionHeader(container, getString(R.string.year_budget_editor_income))
        val overrideAmounts = monthPlan.incomeLines.associate { it.sourceId to it.amount }
        templates.sources.forEach { source ->
            val prefill = overrideAmounts[source.id] ?: 0.0
            incomeInputs[source.id] = addInputRow(container, source.name, prefill)
        }

        addSectionHeader(container, getString(R.string.year_budget_editor_expense))
        val planAmounts = monthPlan.expenseLines.associate { it.categoryId to it.amount }
        templates.expenseCategories.forEach { category ->
            val parent = templates.parentNames[category.parentId]
            val label = if (parent.isNullOrBlank()) category.name else "$parent · ${category.name}"
            expenseInputs[category.id] = addInputRow(container, label, planAmounts[category.id] ?: 0.0)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(title)
            .setView(scroll)
            .setPositiveButton(R.string.year_budget_editor_save, null)
            .setNegativeButton(R.string.year_budget_editor_cancel, null)
            .show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if (saveEditor(monthPlan, incomeInputs, expenseInputs)) dialog.dismiss()
        }
    }

    private fun addSectionHeader(container: LinearLayout, text: String) {
        val view = TextView(this)
        view.text = text
        view.isAllCaps = true
        view.textSize = 12f
        view.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
        container.addView(
            view,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { setMargins(0, resources.getDimensionPixelSize(R.dimen.space_12), 0, resources.getDimensionPixelSize(R.dimen.space_4)) },
        )
    }

    private fun addInputRow(container: LinearLayout, label: String, amount: Double): EditText {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = android.view.Gravity.CENTER_VERTICAL
        val nameView = TextView(this)
        nameView.text = label
        nameView.textSize = 13f
        nameView.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
        nameView.maxLines = 2
        row.addView(
            nameView,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        val input = androidx.appcompat.widget.AppCompatEditText(this)
        input.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
        input.setHintTextColor(ContextCompat.getColor(this, R.color.text_secondary))
        input.backgroundTintList = ContextCompat.getColorStateList(this, R.color.md_theme_primary)
        input.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        input.setText(formatAmount(amount))
        input.textSize = 13f
        input.isSingleLine = true
        row.addView(
            input,
            LinearLayout.LayoutParams(
                (96 * resources.displayMetrics.density).toInt(),
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        container.addView(
            row,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { setMargins(0, 0, 0, resources.getDimensionPixelSize(R.dimen.space_4)) },
        )
        return input
    }

    private fun formatAmount(amount: Double): String {
        return if (amount == amount.toLong().toDouble()) {
            amount.toLong().toString()
        } else {
            String.format(Locale.US, "%.2f", amount)
        }
    }

    private fun parseAmount(text: String): Double {
        val normalized = text.replace(',', '.').trim()
        return normalized.toDoubleOrNull() ?: 0.0
    }

    private fun saveEditor(
        monthPlan: YearBudgetingHelper.MonthPlan,
        incomeInputs: Map<Int, EditText>,
        expenseInputs: Map<Int, EditText>,
    ): Boolean {
        fun values(inputs: Map<Int, EditText>): Map<Int, Double>? {
            val result = linkedMapOf<Int, Double>()
            for ((id, input) in inputs) {
                val text = input.text.toString().trim()
                val value = if (text.isEmpty()) 0.0 else MoneyFormat.parse(text)
                if (value == null || !value.isFinite() || value < 0.0) {
                    input.error = "Укажите сумму не меньше нуля"
                    input.requestFocus()
                    return null
                }
                result[id] = MoneyFormat.roundMoney(value)
            }
            return result
        }
        val incomes = values(incomeInputs) ?: return false
        val expenses = values(expenseInputs) ?: return false
        val selectedBudget = budgetId
        lifecycleScope.launch {
            try {
                manager.database.withTransaction {
                    incomes.forEach { (id, amount) -> manager.repository.upsertMonthlyIncomePlan(
                        MonthlyIncomePlanEntity(monthPlan.year, monthPlan.month, id, selectedBudget, amount, amount > 0.0)) }
                    expenses.forEach { (id, amount) -> manager.repository.upsertMonthlyPlan(
                        MonthlyCategoryPlanEntity(monthPlan.year, monthPlan.month, id, selectedBudget, amount, amount > 0.0)) }
                }
                android.widget.Toast.makeText(this@YearBudgetingActivity, R.string.year_budget_editor_saved, android.widget.Toast.LENGTH_SHORT).show()
                load()
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled
            } catch (error: Exception) {
                android.widget.Toast.makeText(this@YearBudgetingActivity, error.message.orEmpty(), android.widget.Toast.LENGTH_LONG).show()
            }
        }
        return true
    }
    private class MonthAdapter(
        val onToggle: (Int) -> Unit,
        val onEdit: (Int) -> Unit,
    ) : RecyclerView.Adapter<MonthAdapter.Holder>() {
        private var plan: YearBudgetingHelper.YearPlan? = null
        private val expanded = mutableSetOf<Int>()

        fun submit(data: YearBudgetingHelper.YearPlan) {
            plan = data
            notifyDataSetChanged()
        }

        fun toggle(position: Int) {
            if (!expanded.add(position)) expanded.remove(position)
            notifyItemChanged(position)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_year_budget_month, parent, false)
            return Holder(view)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val data = plan ?: return
            holder.bind(data.months[position], expanded.contains(position), onToggle, onEdit)
        }

        override fun getItemCount(): Int = plan?.months?.size ?: 0

        class Holder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val titleView = itemView.findViewById<TextView>(R.id.yearBudgetMonthTitle)
            private val totalsView = itemView.findViewById<TextView>(R.id.yearBudgetMonthTotals)
            private val deltaView = itemView.findViewById<TextView>(R.id.yearBudgetMonthDelta)
            private val detailsView = itemView.findViewById<TextView>(R.id.yearBudgetMonthDetails)
            private val toggleButton = itemView.findViewById<MaterialButton>(R.id.yearBudgetMonthToggle)
            private val editButton = itemView.findViewById<MaterialButton>(R.id.yearBudgetMonthEdit)

            fun bind(
                month: YearBudgetingHelper.MonthPlan,
                expanded: Boolean,
                onToggle: (Int) -> Unit,
                onEdit: (Int) -> Unit,
            ) {
                val context = itemView.context
                titleView.text = MonthBudgetComparisonHelper.monthLabel(month.year, month.month)
                totalsView.text = context.getString(
                    R.string.forecast_vs_fact_income_delta,
                    MoneyFormat.formatRub(month.incomeTotal),
                    month.fact?.let { MoneyFormat.formatRub(it.income) } ?: "—",
                    month.incomeDelta?.let { deltaText(context, it) } ?: "",
                )
                totalsView.append("\n")
                totalsView.append(
                    context.getString(
                        R.string.forecast_vs_fact_expense_delta,
                        MoneyFormat.formatRub(month.expenseTotal),
                        month.fact?.let { MoneyFormat.formatRub(it.expense) } ?: "—",
                        month.expenseDelta?.let { deltaText(context, it) } ?: "",
                    ),
                )
                totalsView.append("\n")
                totalsView.append(context.getString(R.string.year_budget_month_net, MoneyFormat.formatRub(month.net)))
                if (month.netDelta != null) {
                    deltaView.visibility = View.VISIBLE
                    deltaView.text = context.getString(
                        R.string.forecast_vs_fact_net_delta,
                        MoneyFormat.formatRub(month.net),
                        MoneyFormat.formatRub(month.fact!!.income - month.fact.expense),
                        deltaText(context, month.netDelta!!),
                    )
                    deltaView.setTextColor(
                        ContextCompat.getColor(
                            context,
                            if (month.netDelta!!.absolute >= 0.0) R.color.year_budget_positive else R.color.year_budget_negative,
                        ),
                    )
                } else {
                    deltaView.visibility = View.VISIBLE
                    deltaView.text = context.getString(R.string.year_budget_month_net, MoneyFormat.formatRub(month.net))
                    deltaView.setTextColor(
                        ContextCompat.getColor(
                            context,
                            if (month.net >= 0.0) R.color.year_budget_positive else R.color.year_budget_negative,
                        ),
                    )
                }
                editButton.setOnClickListener { onEdit(bindingAdapterPosition) }
                if (!month.incomePlanKnown || !month.expensePlanKnown) {
                    totalsView.text = "Исторический план сохранён не полностью.\nФакт дохода: ${MoneyFormat.formatRub(month.fact?.income ?: 0.0)} · расхода: ${MoneyFormat.formatRub(month.fact?.expense ?: 0.0)}"
                    deltaView.visibility = View.GONE
                }
                val hasLines = month.incomeLines.isNotEmpty() || month.expenseLines.isNotEmpty()
                if (!hasLines) {
                    toggleButton.visibility = View.GONE
                } else {
                    toggleButton.visibility = View.VISIBLE
                    toggleButton.text = context.getString(
                        if (expanded) R.string.forecast_hide_details else R.string.forecast_show_details,
                    )
                    toggleButton.setOnClickListener { onToggle(bindingAdapterPosition) }
                }
                if (expanded && hasLines) {
                    val sb = StringBuilder()
                    month.incomeLines.forEach { line ->
                        sb.append(context.getString(R.string.transactions_income_label))
                            .append(" · ").append(line.name)
                            .append(": ").append(MoneyFormat.formatRub(line.amount))
                            .append('\n')
                    }
                    month.expenseLines.forEach { line ->
                        sb.append(line.name)
                            .append(": ").append(MoneyFormat.formatRub(line.amount))
                            .append('\n')
                    }
                    detailsView.text = sb.toString().trimEnd()
                    detailsView.visibility = View.VISIBLE
                } else {
                    detailsView.visibility = View.GONE
                }
            }

            private fun deltaText(context: android.content.Context, delta: ForecastVsFactHelper.Delta): String {
                val sign = if (delta.absolute >= 0.0) "+" else "−"
                val money = MoneyFormat.formatRub(kotlin.math.abs(delta.absolute))
                val percent = delta.percent?.let { String.format(java.util.Locale.US, "%.0f%%", kotlin.math.abs(it)) }
                    ?: context.getString(R.string.stats_no_data)
                return "$sign$money ($percent)"
            }
        }
    }
}
