package ru.mybudget.app

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
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
import ru.mybudget.app.data.PlannedObligationEntity
import ru.mybudget.app.setup.ObligationPreferences
import java.text.DateFormatSymbols
import java.util.Locale

class PlannedObligationsActivity : AppCompatActivity() {
    private lateinit var manager: BudgetManager
    private lateinit var adapter: ObligationAdapter
    private var budgetId = 1
    private var currentList: List<PlannedObligationEntity> = emptyList()
    private var leafCategories: List<BudgetCategory> = emptyList()
    private var parentNames: Map<Int, String> = emptyMap()
    private val monthNames: Array<String> = DateFormatSymbols(Locale("ru")).months
        .take(12)
        .map { it.replaceFirstChar { ch -> ch.titlecase(Locale("ru")) } }
        .toTypedArray()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_planned_obligations)
        manager = BudgetManager.getInstance(this)
        budgetId = manager.getActiveBudgetId()
        ScreenHeaderHelper.setup(this, getString(R.string.obligations_title))
        MenuRowHelper.bind(
            findViewById(R.id.obligationsPaychecksButton),
            "💵",
            getString(R.string.obligations_paychecks_btn),
        ) { showPaychecksDialog() }
        MenuRowHelper.bind(
            findViewById(R.id.obligationsSyncDefaultsButton),
            "⇄",
            getString(R.string.obligations_sync_defaults_btn),
        ) { syncDefaultAmounts() }
        adapter = ObligationAdapter(
            monthNames = monthNames,
            categoryName = { id -> categoryLabel(id) },
            onEdit = { showEditDialog(it) },
            onCreateReminder = { createReminderFromObligation(it) },
            onDelete = { showDeleteDialog(it) },
        )
        findViewById<RecyclerView>(R.id.obligationsRecycler).apply {
            layoutManager = LinearLayoutManager(this@PlannedObligationsActivity)
            this.adapter = this@PlannedObligationsActivity.adapter
        }
        findViewById<View>(R.id.addObligationButton).setOnClickListener { showEditDialog(null) }
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun reload() {
        budgetId = manager.getActiveBudgetId()
        lifecycleScope.launch {
            manager.getCategoriesAsync()
            refreshLeaves()
            val list = manager.repository.getPlannedObligationsByBudgetOnce(budgetId)
            currentList = list
            adapter.submit(list)
            findViewById<View>(R.id.obligationsEmptyState).visibility =
                if (list.isEmpty()) View.VISIBLE else View.GONE
            updateSummary(list)
        }
    }

    private fun refreshLeaves() {
        parentNames = manager.getRootCategories(budgetId).associate { it.id to it.name }
        leafCategories = manager.getCategoriesForBudget(budgetId)
            .filter { !manager.hasSubcategories(it.id) }
    }

    private fun categoryLabel(categoryId: Int): String {
        if (categoryId <= 0) return getString(R.string.obligations_no_category)
        val category = leafCategories.firstOrNull { it.id == categoryId }
            ?: manager.getCategories().firstOrNull { it.id == categoryId }
        return category?.let { CategoryMultiPicker.leafLabel(it, parentNames) }
            ?: getString(R.string.obligations_no_category)
    }

    private fun updateSummary(list: List<PlannedObligationEntity>) {
        val monthly = PlannedObligationHelper.totalMonthly(list)
        val perPaycheck = PlannedObligationHelper.totalPerPaycheck(list)
        findViewById<TextView>(R.id.obligationsSummaryMonthly).text =
            getString(R.string.obligations_summary_monthly, MoneyFormat.formatRub(monthly))
        findViewById<TextView>(R.id.obligationsSummaryPerPaycheck).text =
            getString(R.string.obligations_summary_per_paycheck, MoneyFormat.formatRub(perPaycheck))
        val unlinked = PlannedObligationHelper.unlinkedCount(list)
        val paychecks = ObligationPreferences.getPaychecksPerMonth(this)
        val hint = buildString {
            append(getString(R.string.obligations_default_paychecks, paychecks))
            if (unlinked > 0) {
                append('\n')
                append(getString(R.string.obligations_unlinked_hint, unlinked))
            }
        }
        findViewById<TextView>(R.id.obligationsPaychecksHint).apply {
            text = hint
            visibility = View.VISIBLE
        }
    }

    private fun showPaychecksDialog() {
        val options = arrayOf("1", "2", "3", "4")
        val current = (ObligationPreferences.getPaychecksPerMonth(this) - 1).coerceIn(0, 3)
        AlertDialog.Builder(this)
            .setTitle(R.string.obligations_paychecks_dialog_title)
            .setSingleChoiceItems(options, current) { dialog, which ->
                ObligationPreferences.setPaychecksPerMonth(this, which + 1)
                updateSummary(currentList)
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun syncDefaultAmounts() {
        val map = PlannedObligationHelper.distributionByCategory(currentList)
        if (map.isEmpty()) {
            Toast.makeText(this, R.string.obligations_sync_nothing, Toast.LENGTH_LONG).show()
            return
        }
        lifecycleScope.launch(Dispatchers.IO) {
            map.forEach { (categoryId, amount) ->
                manager.repository.updateDefaultIncomeAmount(categoryId, amount)
            }
            manager.getCategoriesAsync(forceReload = true)
            withContext(Dispatchers.Main) {
                Toast.makeText(this@PlannedObligationsActivity, R.string.obligations_sync_done, Toast.LENGTH_SHORT).show()
                reload()
            }
        }
    }

    private fun createReminderFromObligation(item: PlannedObligationEntity) {
        if (item.categoryId <= 0) {
            Toast.makeText(this, R.string.obligations_reminder_need_category, Toast.LENGTH_LONG).show()
            return
        }
        val reminder = ObligationReminderHelper.buildReminder(item, categoryLabel(item.categoryId)) ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            manager.repository.insertReminder(reminder)
            withContext(Dispatchers.Main) {
                Toast.makeText(this@PlannedObligationsActivity, R.string.obligations_reminder_created, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showDeleteDialog(item: PlannedObligationEntity) {
        AlertDialog.Builder(this)
            .setTitle(R.string.obligations_delete_title)
            .setMessage(getString(R.string.obligations_delete_msg, item.name))
            .setPositiveButton(R.string.budget_delete_selected) { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    manager.repository.deletePlannedObligation(item.id)
                    withContext(Dispatchers.Main) { reload() }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showEditDialog(existing: PlannedObligationEntity?) {
        lifecycleScope.launch {
            manager.getCategoriesAsync()
            refreshLeaves()
            if (leafCategories.isEmpty() && existing == null) {
                Toast.makeText(this@PlannedObligationsActivity, R.string.obligations_need_categories, Toast.LENGTH_LONG).show()
            }
            openEditDialog(existing)
        }
    }

    private fun openEditDialog(existing: PlannedObligationEntity?) {
        val inflate = layoutInflater.inflate(R.layout.dialog_add_planned_obligation, null)
        val nameInput = inflate.findViewById<EditText>(R.id.obligationNameInput)
        val amountInput = inflate.findViewById<EditText>(R.id.obligationAmountInput)
        val periodSpinner = inflate.findViewById<Spinner>(R.id.obligationPeriodSpinner)
        val dueMonthLabel = inflate.findViewById<TextView>(R.id.obligationDueMonthLabel)
        val dueMonthSpinner = inflate.findViewById<Spinner>(R.id.obligationDueMonthSpinner)
        val categorySpinner = inflate.findViewById<Spinner>(R.id.obligationCategorySpinner)
        val paychecksSpinner = inflate.findViewById<Spinner>(R.id.obligationPaychecksSpinner)
        val previewLine = inflate.findViewById<TextView>(R.id.obligationPreviewLine)

        periodSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            listOf(getString(R.string.obligations_period_monthly), getString(R.string.obligations_period_yearly)),
        )
        dueMonthSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            monthNames.toList(),
        )
        val categoryLabels = listOf(getString(R.string.obligations_no_category)) +
            leafCategories.map { CategoryMultiPicker.leafLabel(it, parentNames) }
        categorySpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            categoryLabels,
        )
        paychecksSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            (1..4).map { getString(R.string.obligations_paychecks_option, it) },
        )

        if (existing != null) {
            nameInput.setText(existing.name)
            amountInput.setText(MoneyFormat.format(existing.amount))
            periodSpinner.setSelection(if (existing.periodType == PlannedObligationHelper.PERIOD_YEARLY) 1 else 0)
            dueMonthSpinner.setSelection((existing.dueMonth - 1).coerceIn(0, 11))
            val catIndex = leafCategories.indexOfFirst { it.id == existing.categoryId }
            categorySpinner.setSelection(if (catIndex >= 0) catIndex + 1 else 0)
            paychecksSpinner.setSelection((existing.paychecksPerMonth - 1).coerceIn(0, 3))
        } else {
            paychecksSpinner.setSelection((ObligationPreferences.getPaychecksPerMonth(this) - 1).coerceIn(0, 3))
        }

        val updatePreview = {
            updateDueMonthVisibility(periodSpinner, dueMonthLabel, dueMonthSpinner)
            val amount = MoneyFormat.parse(amountInput.text) ?: 0.0
            if (amount <= 0.0) {
                previewLine.text = ""
            } else {
                val period = if (periodSpinner.selectedItemPosition == 1) {
                    PlannedObligationHelper.PERIOD_YEARLY
                } else {
                    PlannedObligationHelper.PERIOD_MONTHLY
                }
                val paychecks = paychecksSpinner.selectedItemPosition + 1
                val draft = PlannedObligationEntity(
                    budgetId = 0,
                    name = "",
                    amount = amount,
                    periodType = period,
                    categoryId = 0,
                    paychecksPerMonth = paychecks,
                    dueMonth = 1,
                )
                val per = PlannedObligationHelper.perPaycheck(draft)
                previewLine.text = getString(R.string.obligations_preview, MoneyFormat.formatRub(per), paychecks)
            }
        }
        periodSpinner.onItemSelectedListener = simpleItemSelected { updatePreview() }
        paychecksSpinner.onItemSelectedListener = simpleItemSelected { updatePreview() }
        amountInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) { updatePreview() }
        })
        updatePreview()

        AlertDialog.Builder(this)
            .setTitle(if (existing == null) R.string.obligations_add else R.string.obligations_edit)
            .setView(inflate)
            .setPositiveButton(R.string.budget_add_category_btn) { _, _ ->
                val name = nameInput.text.toString().trim()
                val amount = MoneyFormat.parse(amountInput.text)
                if (name.isBlank() || amount == null || amount <= 0.0) {
                    Toast.makeText(this, R.string.obligations_validation, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val period = if (periodSpinner.selectedItemPosition == 1) {
                    PlannedObligationHelper.PERIOD_YEARLY
                } else {
                    PlannedObligationHelper.PERIOD_MONTHLY
                }
                val catPos = categorySpinner.selectedItemPosition
                val categoryId = if (catPos <= 0) 0 else leafCategories[catPos - 1].id
                val entity = PlannedObligationEntity(
                    id = existing?.id ?: 0,
                    budgetId = budgetId,
                    name = name,
                    amount = amount,
                    periodType = period,
                    categoryId = categoryId,
                    paychecksPerMonth = paychecksSpinner.selectedItemPosition + 1,
                    dueMonth = dueMonthSpinner.selectedItemPosition + 1,
                    createdAt = existing?.createdAt ?: System.currentTimeMillis(),
                )
                lifecycleScope.launch(Dispatchers.IO) {
                    if (existing == null) {
                        manager.repository.insertPlannedObligation(entity)
                    } else {
                        manager.repository.updatePlannedObligation(entity)
                    }
                    withContext(Dispatchers.Main) { reload() }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun updateDueMonthVisibility(periodSpinner: Spinner, label: TextView, spinner: Spinner) {
        val yearly = periodSpinner.selectedItemPosition == 1
        val vis = if (yearly) View.VISIBLE else View.GONE
        label.visibility = vis
        spinner.visibility = vis
    }

    private fun simpleItemSelected(onChange: () -> Unit) = object : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
            onChange()
        }
        override fun onNothingSelected(parent: AdapterView<*>?) = Unit
    }

    private class ObligationAdapter(
        private val monthNames: Array<String>,
        private val categoryName: (Int) -> String,
        private val onEdit: (PlannedObligationEntity) -> Unit,
        private val onCreateReminder: (PlannedObligationEntity) -> Unit,
        private val onDelete: (PlannedObligationEntity) -> Unit,
    ) : RecyclerView.Adapter<ObligationAdapter.Holder>() {
        private var items: List<PlannedObligationEntity> = emptyList()

        fun submit(list: List<PlannedObligationEntity>) {
            items = list
            notifyDataSetChanged()
        }

        class Holder(v: View) : RecyclerView.ViewHolder(v) {
            val name: TextView = v.findViewById(R.id.obligationName)
            val badge: TextView = v.findViewById(R.id.obligationPeriodBadge)
            val amountLine: TextView = v.findViewById(R.id.obligationAmountLine)
            val perPaycheckLine: TextView = v.findViewById(R.id.obligationPerPaycheckLine)
            val categoryLine: TextView = v.findViewById(R.id.obligationCategoryLine)
            val dueLine: TextView = v.findViewById(R.id.obligationDueLine)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_planned_obligation, parent, false)
            return Holder(v)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = items[position]
            val ctx = holder.itemView.context
            val isYearly = item.periodType == PlannedObligationHelper.PERIOD_YEARLY
            holder.name.text = item.name
            holder.badge.text = ctx.getString(
                if (isYearly) R.string.obligations_period_yearly_short else R.string.obligations_period_monthly_short,
            )
            holder.amountLine.text = ctx.getString(
                if (isYearly) R.string.obligations_amount_yearly else R.string.obligations_amount_monthly,
                MoneyFormat.formatRub(item.amount),
            )
            val per = PlannedObligationHelper.perPaycheck(item)
            holder.perPaycheckLine.text = ctx.getString(
                R.string.obligations_item_per_paycheck,
                MoneyFormat.formatRub(per),
                item.paychecksPerMonth,
            )
            holder.categoryLine.text = ctx.getString(R.string.obligations_item_category, categoryName(item.categoryId))
            if (isYearly) {
                holder.dueLine.visibility = View.VISIBLE
                holder.dueLine.text = ctx.getString(
                    R.string.obligations_item_due_month,
                    monthNames[(item.dueMonth - 1).coerceIn(0, 11)],
                )
            } else {
                holder.dueLine.visibility = View.GONE
            }
            holder.itemView.setOnClickListener { onEdit(item) }
            holder.itemView.setOnLongClickListener {
                AlertDialog.Builder(ctx)
                    .setTitle(item.name)
                    .setItems(
                        arrayOf(
                            ctx.getString(R.string.obligations_edit),
                            ctx.getString(R.string.obligations_create_reminder),
                            ctx.getString(R.string.budget_delete_selected),
                        ),
                    ) { _, which ->
                        when (which) {
                            0 -> onEdit(item)
                            1 -> onCreateReminder(item)
                            2 -> onDelete(item)
                        }
                    }
                    .show()
                true
            }
        }

        override fun getItemCount(): Int = items.size
    }
}
