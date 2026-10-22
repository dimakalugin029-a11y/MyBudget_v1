package ru.mybudget.app

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.mybudget.app.data.PlannedIncomeSourceEntity
import java.util.Locale

class PlannedIncomeActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_AUTO_ADD = "extra_auto_add"
        const val EXTRA_PRESET_DAY_OF_MONTH = "extra_preset_day_of_month"
    }
    private lateinit var manager: BudgetManager
    private lateinit var adapter: IncomeSourceAdapter
    private var budgetId = 1
    private var currentList: List<PlannedIncomeSourceEntity> = emptyList()
    private var obligationsMonthly = 0.0
    private var sourceBalances: Map<Int, PlannedIncomeHelper.SourceBalance> = emptyMap()
    private var selectedMonth = java.time.YearMonth.now()
    private var progressBySource: Map<Int, IncomePlanAmounts.Progress> = emptyMap()
    private var reloadJob: kotlinx.coroutines.Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_planned_income)
        manager = BudgetManager.getInstance(this)
        selectedMonth = savedInstanceState?.getString("income_month")?.let { java.time.YearMonth.parse(it) }
            ?: java.time.YearMonth.now()
        budgetId = manager.getActiveBudgetId()
        ScreenHeaderHelper.setup(
            this,
            getString(R.string.income_plan_title),
            getString(R.string.main_icon_income_plan),
        )
        ScreenHeaderHelper.bindAction(
            this,
            android.R.drawable.ic_input_add,
            R.string.income_plan_add,
        ) { showEditDialog(null) }
        ScreenHintHelper.bind(
            this,
            findViewById(R.id.incomePlanHint),
            ScreenHintHelper.Keys.PLANNED_INCOME,
            R.string.hint_planned_income,
        )
        findViewById<TextView>(R.id.incomePlanObligationsButton).setOnClickListener {
            startActivity(Intent(this, PlannedObligationsActivity::class.java))
        }
        adapter = IncomeSourceAdapter(
            onEdit = { showEditDialog(it) },
            onDelete = { showDeleteDialog(it) },
            balanceFor = { id -> sourceBalances[id] },
            progressFor = { id -> progressBySource[id] },
            onReceive = { source ->
                val remaining = progressBySource[source.id]?.remaining ?: 0.0
                if (remaining > 0.0) startActivity(Intent(this, IncomeActivity::class.java)
                    .putExtra(BudgetIntentExtras.PLANNED_INCOME_SOURCE_ID, source.id)
                    .putExtra(BudgetIntentExtras.PLANNED_INCOME_MONTH, selectedMonth.toString())
                    .putExtra(BudgetIntentExtras.PLANNED_INCOME_AMOUNT, remaining)
                    .putExtra(BudgetIntentExtras.PLANNED_INCOME_NAME, source.name))
            },
        )
        findViewById<View>(R.id.incomePlanPreviousMonth).setOnClickListener { selectedMonth = selectedMonth.minusMonths(1); reload() }
        findViewById<View>(R.id.incomePlanNextMonth).setOnClickListener { selectedMonth = selectedMonth.plusMonths(1); reload() }
        findViewById<RecyclerView>(R.id.incomePlanRecycler).apply {
            layoutManager = LinearLayoutManager(this@PlannedIncomeActivity)
            this.adapter = this@PlannedIncomeActivity.adapter
        }
        if (intent.getBooleanExtra(EXTRA_AUTO_ADD, false)) {
            val presetDay = intent.getIntExtra(EXTRA_PRESET_DAY_OF_MONTH, 10)
            showEditDialog(null, presetDay)
        }
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun reload() {
        reloadJob?.cancel()
        budgetId = manager.getActiveBudgetId()
        val activeId = budgetId
        val month = selectedMonth
        findViewById<TextView>(R.id.incomePlanMonth).text = month.format(java.time.format.DateTimeFormatter.ofPattern("LLLL yyyy", Locale("ru")))
        reloadJob = lifecycleScope.launch {
            val sources = manager.repository.getPlannedIncomeSourcesByBudgetOnce(activeId)
            val obligations = manager.repository.getPlannedObligationsByBudgetOnce(activeId)
            val dao = manager.database.budgetDao()
            val overrides = dao.getMonthlyIncomePlansForMonth(activeId, month.year, month.monthValue)
            val receipts = dao.getIncomePlanTransactions(activeId)
            if (activeId != manager.getActiveBudgetId() || month != selectedMonth) return@launch
            progressBySource = sources.associate { it.id to IncomePlanAmounts.progress(it, month, overrides, receipts) }
            currentList = sources
            obligationsMonthly = PlannedObligationHelper.totalMonthly(obligations)
            sourceBalances = PlannedIncomeHelper.balanceBySource(sources, obligations)
                .associateBy { it.source.id }
            adapter.submit(sources)
            findViewById<View>(R.id.incomePlanEmptyState).visibility =
                if (sources.isEmpty()) View.VISIBLE else View.GONE
            updateSummary(sources)
        }
    }

    private fun updateSummary(sources: List<PlannedIncomeSourceEntity>) {
        val incomeMonthly = PlannedIncomeHelper.monthlyTotal(sources)
        findViewById<TextView>(R.id.incomePlanSummaryTotal).text =
            getString(R.string.income_plan_month_total, selectedMonth.toString(), MoneyFormat.formatRub(progressBySource.values.sumOf { it.planned }))
        findViewById<TextView>(R.id.incomePlanSummaryProgress).text = getString(R.string.income_plan_progress,
            MoneyFormat.formatRub(progressBySource.values.sumOf { it.planned }), MoneyFormat.formatRub(progressBySource.values.sumOf { it.received }),
            MoneyFormat.formatRub(progressBySource.values.sumOf { it.remaining }))
        findViewById<TextView>(R.id.incomePlanSummaryAverage).text = getString(R.string.income_plan_average, MoneyFormat.formatRub(incomeMonthly))
        findViewById<TextView>(R.id.incomePlanSummaryBalance).text = getString(R.string.income_plan_after_obligations,
            MoneyFormat.formatRub(PlannedIncomeHelper.freeAfterObligations(incomeMonthly, obligationsMonthly)), MoneyFormat.formatRub(obligationsMonthly))
        findViewById<TextView>(R.id.incomePlanSummaryHint).visibility = View.GONE
    }

    private fun showDeleteDialog(item: PlannedIncomeSourceEntity) {
        AlertDialog.Builder(this)
            .setTitle(R.string.income_plan_delete_title)
            .setMessage(getString(R.string.income_plan_delete_msg, item.name))
            .setPositiveButton(R.string.budget_delete_selected) { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    manager.repository.deletePlannedIncomeSource(item.id)
                    withContext(Dispatchers.Main) { reload() }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showEditDialog(existing: PlannedIncomeSourceEntity?, presetDay: Int = 10) {
        val inflate = layoutInflater.inflate(R.layout.dialog_add_planned_income, null)
        val nameInput = inflate.findViewById<EditText>(R.id.incomeSourceNameInput)
        val amountInput = inflate.findViewById<EditText>(R.id.incomeSourceAmountInput)
        val amountLabel = inflate.findViewById<TextView>(R.id.incomeSourceAmountLabel)
        val typeSpinner = inflate.findViewById<Spinner>(R.id.incomeSourceTypeSpinner)
        val periodLabel = inflate.findViewById<TextView>(R.id.incomeSourcePeriodLabel)
        val periodSpinner = inflate.findViewById<Spinner>(R.id.incomeSourcePeriodSpinner)
        val monthLabel = inflate.findViewById<TextView>(R.id.incomeSourceMonthLabel)
        val monthSpinner = inflate.findViewById<Spinner>(R.id.incomeSourceMonthSpinner)
        val daySpinner = inflate.findViewById<Spinner>(R.id.incomeSourceDaySpinner)
        typeSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            listOf(
                getString(R.string.income_plan_type_salary),
                getString(R.string.income_plan_type_advance),
                getString(R.string.income_plan_type_bonus),
                getString(R.string.income_plan_type_other),
            ),
        )
        periodSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            listOf(
                getString(R.string.income_plan_period_monthly),
                getString(R.string.income_plan_period_quarterly),
                getString(R.string.income_plan_period_yearly),
            ),
        )
        val monthNames = java.text.DateFormatSymbols(Locale("ru")).months
            .take(12)
            .map { it.replaceFirstChar { ch -> ch.titlecase(Locale("ru")) } }
        monthSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            monthNames,
        )
        val dayLabels = (1..31).map { getString(R.string.obligations_due_day_number, it) } +
            getString(R.string.income_plan_day_flexible)
        daySpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            dayLabels,
        )

        fun updateBonusFields() {
            val isBonus = typeSpinner.selectedItemPosition == 2
            val periodVisible = if (isBonus) View.VISIBLE else View.GONE
            periodLabel.visibility = periodVisible
            periodSpinner.visibility = periodVisible
            val period = PlannedIncomeHelper.periodFromSpinnerPosition(periodSpinner.selectedItemPosition)
            val monthVisible = if (isBonus && period != PlannedIncomeHelper.PERIOD_MONTHLY) {
                View.VISIBLE
            } else {
                View.GONE
            }
            monthLabel.visibility = monthVisible
            monthSpinner.visibility = monthVisible
            amountLabel.text = when {
                !isBonus -> getString(R.string.income_plan_field_amount)
                period == PlannedIncomeHelper.PERIOD_YEARLY -> getString(R.string.income_plan_field_amount_yearly)
                period == PlannedIncomeHelper.PERIOD_QUARTERLY -> getString(R.string.income_plan_field_amount_quarterly)
                else -> getString(R.string.income_plan_field_amount)
            }
            monthLabel.text = if (period == PlannedIncomeHelper.PERIOD_QUARTERLY) {
                getString(R.string.income_plan_field_quarter_start_month)
            } else {
                getString(R.string.income_plan_field_month)
            }
        }

        typeSpinner.onItemSelectedListener = simpleItemSelected { updateBonusFields() }
        periodSpinner.onItemSelectedListener = simpleItemSelected { updateBonusFields() }

        if (existing != null) {
            nameInput.setText(existing.name)
            amountInput.setText(MoneyFormat.format(existing.amount))
            typeSpinner.setSelection(PlannedIncomeHelper.typeSpinnerPosition(existing.sourceType))
            periodSpinner.setSelection(PlannedIncomeHelper.periodSpinnerPosition(existing.periodType))
            monthSpinner.setSelection((existing.dueMonth - 1).coerceIn(0, 11))
            daySpinner.setSelection(PlannedIncomeHelper.daySpinnerPosition(existing.dayOfMonth))
        } else {
            typeSpinner.setSelection(0)
            periodSpinner.setSelection(0)
            monthSpinner.setSelection(2)
            daySpinner.setSelection(PlannedIncomeHelper.daySpinnerPosition(presetDay))
        }
        updateBonusFields()

        AlertDialog.Builder(this)
            .setTitle(if (existing == null) R.string.income_plan_add else R.string.income_plan_edit)
            .setView(inflate)
            .setPositiveButton(R.string.budget_add_category_btn) { _, _ ->
                val name = nameInput.text.toString().trim()
                val amount = MoneyFormat.parse(amountInput.text)
                if (name.isBlank() || amount == null || amount <= 0.0) {
                    Toast.makeText(this, R.string.income_plan_validation, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val sourceType = PlannedIncomeHelper.typeFromSpinnerPosition(typeSpinner.selectedItemPosition)
                val periodType = if (sourceType == PlannedIncomeHelper.TYPE_BONUS) {
                    PlannedIncomeHelper.periodFromSpinnerPosition(periodSpinner.selectedItemPosition)
                } else {
                    PlannedIncomeHelper.PERIOD_MONTHLY
                }
                val entity = PlannedIncomeSourceEntity(
                    id = existing?.id ?: 0,
                    budgetId = budgetId,
                    name = name,
                    amount = amount,
                    sourceType = sourceType,
                    dayOfMonth = PlannedIncomeHelper.dayFromSpinnerPosition(daySpinner.selectedItemPosition),
                    periodType = periodType,
                    dueMonth = monthSpinner.selectedItemPosition + 1,
                    sortOrder = existing?.sortOrder ?: currentList.size,
                    createdAt = existing?.createdAt ?: System.currentTimeMillis(),
                )
                lifecycleScope.launch(Dispatchers.IO) {
                    if (existing == null) {
                        manager.repository.insertPlannedIncomeSource(entity)
                    } else {
                        manager.repository.updatePlannedIncomeSource(entity)
                    }
                    withContext(Dispatchers.Main) { reload() }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun simpleItemSelected(onChange: () -> Unit) = object : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
            onChange()
        }
        override fun onNothingSelected(parent: AdapterView<*>?) = Unit
    }

    private class IncomeSourceAdapter(
        private val onEdit: (PlannedIncomeSourceEntity) -> Unit,
        private val onDelete: (PlannedIncomeSourceEntity) -> Unit,
        private val balanceFor: (Int) -> PlannedIncomeHelper.SourceBalance?,
        private val progressFor: (Int) -> IncomePlanAmounts.Progress?,
        private val onReceive: (PlannedIncomeSourceEntity) -> Unit,
    ) : RecyclerView.Adapter<IncomeSourceAdapter.Holder>() {
        private var items: List<PlannedIncomeSourceEntity> = emptyList()

        fun submit(list: List<PlannedIncomeSourceEntity>) {
            items = list
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_planned_income, parent, false)
            return Holder(v)
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = items[position]
            val ctx = holder.itemView.context
            holder.name.text = item.name
            holder.typeBadge.text = PlannedIncomeHelper.typeLabel(ctx, item.sourceType)
            holder.amountLine.text = PlannedIncomeHelper.amountLine(ctx, item)
            holder.dayLine.text = PlannedIncomeHelper.scheduleLabel(ctx, item)
            val progress = progressFor(item.id)
            holder.progressLine.text = progress?.let { ctx.getString(R.string.income_plan_progress,
                MoneyFormat.formatRub(it.planned), MoneyFormat.formatRub(it.received), MoneyFormat.formatRub(it.remaining)) }.orEmpty()
            holder.receive.text = ctx.getString(when {
                progress == null || progress.planned <= 0.0 && progress.received <= 0.0 -> R.string.income_plan_no_payment
                progress.remaining <= 0.0 -> R.string.income_plan_received
                else -> R.string.income_plan_receive
            })
            holder.receive.isEnabled = (progress?.remaining ?: 0.0) > 0.0
            holder.receive.setOnClickListener { onReceive(item) }
            val balance = balanceFor(item.id)
            if (balance != null && balance.linkedObligationsMonthly > 0.0) {
                holder.obligationsLine.visibility = View.VISIBLE
                if (balance.freeAmount < 0.0) {
                    holder.obligationsLine.setTextColor(
                        ctx.getColor(R.color.expense_red),
                    )
                    holder.obligationsLine.text = ctx.getString(
                        R.string.income_plan_item_obligations_deficit,
                        MoneyFormat.formatRub(-balance.freeAmount),
                    )
                } else {
                    holder.obligationsLine.setTextColor(
                        ctx.getColor(R.color.text_secondary),
                    )
                    holder.obligationsLine.text = ctx.getString(
                        R.string.income_plan_item_obligations,
                        MoneyFormat.formatRub(balance.linkedObligationsMonthly),
                        MoneyFormat.formatRub(balance.freeAmount),
                    )
                }
            } else {
                holder.obligationsLine.visibility = View.GONE
            }
            holder.itemView.setOnClickListener { onEdit(item) }
            holder.itemView.setOnLongClickListener {
                AlertDialog.Builder(ctx)
                    .setTitle(item.name)
                    .setItems(
                        arrayOf(
                            ctx.getString(R.string.income_plan_edit),
                            ctx.getString(R.string.budget_delete_selected),
                        ),
                    ) { _, which ->
                        when (which) {
                            0 -> onEdit(item)
                            1 -> onDelete(item)
                        }
                    }
                    .show()
                true
            }
        }

        class Holder(v: View) : RecyclerView.ViewHolder(v) {
            val name: TextView = v.findViewById(R.id.incomeSourceName)
            val typeBadge: TextView = v.findViewById(R.id.incomeSourceTypeBadge)
            val amountLine: TextView = v.findViewById(R.id.incomeSourceAmountLine)
            val dayLine: TextView = v.findViewById(R.id.incomeSourceDayLine)
            val obligationsLine: TextView = v.findViewById(R.id.incomeSourceObligationsLine)
            val progressLine: TextView = v.findViewById(R.id.incomeSourceProgress)
            val receive: TextView = v.findViewById(R.id.incomeSourceReceive)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("income_month", selectedMonth.toString())
        super.onSaveInstanceState(outState)
    }
}
