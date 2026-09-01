package ru.mybudget.app

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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

class PlannedIncomeActivity : AppCompatActivity() {
    private lateinit var manager: BudgetManager
    private lateinit var adapter: IncomeSourceAdapter
    private var budgetId = 1
    private var currentList: List<PlannedIncomeSourceEntity> = emptyList()
    private var obligationsMonthly = 0.0
    private var sourceBalances: Map<Int, PlannedIncomeHelper.SourceBalance> = emptyMap()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_planned_income)
        manager = BudgetManager.getInstance(this)
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
        )
        findViewById<RecyclerView>(R.id.incomePlanRecycler).apply {
            layoutManager = LinearLayoutManager(this@PlannedIncomeActivity)
            this.adapter = this@PlannedIncomeActivity.adapter
        }
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun reload() {
        budgetId = manager.getActiveBudgetId()
        lifecycleScope.launch {
            val sources = manager.repository.getPlannedIncomeSourcesByBudgetOnce(budgetId)
            val obligations = manager.repository.getPlannedObligationsByBudgetOnce(budgetId)
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
            getString(R.string.income_plan_summary_total, MoneyFormat.formatRub(incomeMonthly))
        if (obligationsMonthly > 0.0) {
            val free = PlannedIncomeHelper.freeAfterObligations(incomeMonthly, obligationsMonthly)
            findViewById<TextView>(R.id.incomePlanSummaryBalance).text =
                getString(
                    R.string.income_plan_summary_balance,
                    MoneyFormat.formatRub(obligationsMonthly),
                    MoneyFormat.formatRub(free),
                )
            findViewById<TextView>(R.id.incomePlanSummaryHint).apply {
                visibility = if (free < 0.0) View.VISIBLE else View.GONE
                if (free < 0.0) {
                    text = getString(R.string.income_plan_summary_deficit, MoneyFormat.formatRub(-free))
                }
            }
        } else if (incomeMonthly > 0.0) {
            findViewById<TextView>(R.id.incomePlanSummaryBalance).text =
                getString(R.string.income_plan_summary_no_obligations)
            findViewById<TextView>(R.id.incomePlanSummaryHint).visibility = View.GONE
        } else {
            findViewById<TextView>(R.id.incomePlanSummaryBalance).text =
                getString(R.string.income_plan_summary_empty)
            findViewById<TextView>(R.id.incomePlanSummaryHint).visibility = View.GONE
        }
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

    private fun showEditDialog(existing: PlannedIncomeSourceEntity?) {
        val inflate = layoutInflater.inflate(R.layout.dialog_add_planned_income, null)
        val nameInput = inflate.findViewById<EditText>(R.id.incomeSourceNameInput)
        val amountInput = inflate.findViewById<EditText>(R.id.incomeSourceAmountInput)
        val typeSpinner = inflate.findViewById<Spinner>(R.id.incomeSourceTypeSpinner)
        val daySpinner = inflate.findViewById<Spinner>(R.id.incomeSourceDaySpinner)
        typeSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            listOf(
                getString(R.string.income_plan_type_salary),
                getString(R.string.income_plan_type_advance),
                getString(R.string.income_plan_type_other),
            ),
        )
        val dayLabels = (1..31).map { getString(R.string.obligations_due_day_number, it) } +
            getString(R.string.income_plan_day_flexible)
        daySpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            dayLabels,
        )
        if (existing != null) {
            nameInput.setText(existing.name)
            amountInput.setText(MoneyFormat.format(existing.amount))
            typeSpinner.setSelection(PlannedIncomeHelper.typeSpinnerPosition(existing.sourceType))
            daySpinner.setSelection(PlannedIncomeHelper.daySpinnerPosition(existing.dayOfMonth))
        } else {
            typeSpinner.setSelection(0)
            daySpinner.setSelection(PlannedIncomeHelper.daySpinnerPosition(10))
        }
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
                val entity = PlannedIncomeSourceEntity(
                    id = existing?.id ?: 0,
                    budgetId = budgetId,
                    name = name,
                    amount = amount,
                    sourceType = PlannedIncomeHelper.typeFromSpinnerPosition(typeSpinner.selectedItemPosition),
                    dayOfMonth = PlannedIncomeHelper.dayFromSpinnerPosition(daySpinner.selectedItemPosition),
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

    private class IncomeSourceAdapter(
        private val onEdit: (PlannedIncomeSourceEntity) -> Unit,
        private val onDelete: (PlannedIncomeSourceEntity) -> Unit,
        private val balanceFor: (Int) -> PlannedIncomeHelper.SourceBalance?,
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
            holder.amountLine.text = ctx.getString(
                R.string.income_plan_item_amount,
                MoneyFormat.formatRub(item.amount),
            )
            holder.dayLine.text = PlannedIncomeHelper.dayLabel(ctx, item.dayOfMonth)
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
        }
    }
}
