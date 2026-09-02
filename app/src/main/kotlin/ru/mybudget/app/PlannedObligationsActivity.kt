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
import androidx.appcompat.widget.SwitchCompat
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.mybudget.app.data.PlannedIncomeSourceEntity
import ru.mybudget.app.data.PlannedObligationEntity
import ru.mybudget.app.setup.ObligationPreferences
import java.text.DateFormatSymbols
import java.util.Locale

class PlannedObligationsActivity : AppCompatActivity() {
    private lateinit var manager: BudgetManager
    private lateinit var adapter: ObligationAdapter
    private var budgetId = 1
    private var currentList: List<PlannedObligationEntity> = emptyList()
    private var paidPeriods: Set<ObligationPaymentHelper.PeriodKey> = emptySet()
    private var incomeSources: List<PlannedIncomeSourceEntity> = emptyList()
    private var currentKindFilter = PlannedObligationHelper.KIND_FILTER_ALL
    private lateinit var kindFilterViews: Map<String, TextView>
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
        ScreenHeaderHelper.setup(this, getString(R.string.obligations_title), getString(R.string.main_icon_obligations))
        ScreenHeaderHelper.bindAction(
            this,
            android.R.drawable.ic_input_add,
            R.string.obligations_add,
        ) { showEditDialog(null) }
        ScreenHintHelper.bind(
            this,
            findViewById(R.id.obligationsHint),
            ScreenHintHelper.Keys.PLANNED_OBLIGATIONS,
            R.string.hint_planned_obligations,
        )
        findViewById<TextView>(R.id.obligationsPaychecksButton).setOnClickListener { showPaychecksDialog() }
        findViewById<TextView>(R.id.obligationsSyncDefaultsButton).setOnClickListener {
            syncDefaultAmounts()
        }
        kindFilterViews = mapOf(
            PlannedObligationHelper.KIND_FILTER_ALL to findViewById(R.id.obligationsFilterAll),
            PlannedObligationHelper.KIND_CREDIT to findViewById(R.id.obligationsFilterCredit),
            PlannedObligationHelper.KIND_UTILITIES to findViewById(R.id.obligationsFilterUtilities),
            PlannedObligationHelper.KIND_SUBSCRIPTION to findViewById(R.id.obligationsFilterSubscription),
            PlannedObligationHelper.KIND_OTHER to findViewById(R.id.obligationsFilterOther),
        )
        kindFilterViews.forEach { (kind, view) ->
            view.setOnClickListener { applyKindFilter(kind) }
        }
        adapter = ObligationAdapter(
            monthNames = monthNames,
            categoryName = { id -> categoryLabel(id) },
            incomeSourceName = { id -> incomeSources.firstOrNull { it.id == id }?.name },
            paidPeriods = { paidPeriods },
            onEdit = { showEditDialog(it) },
            onDelete = { showDeleteDialog(it) },
            onPay = { payObligation(it) },
        )
        findViewById<RecyclerView>(R.id.obligationsRecycler).apply {
            layoutManager = LinearLayoutManager(this@PlannedObligationsActivity)
            this.adapter = this@PlannedObligationsActivity.adapter
        }
        if (intent.getBooleanExtra(PlanningEntryWizard.EXTRA_AUTO_ADD, false)) {
            intent.removeExtra(PlanningEntryWizard.EXTRA_AUTO_ADD)
            lifecycleScope.launch {
                manager.getCategoriesAsync()
                refreshLeaves()
                showEditDialog(null)
            }
        }
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
            incomeSources = manager.repository.getPlannedIncomeSourcesByBudgetOnce(budgetId)
            paidPeriods = ObligationPaymentHelper.paidKeys(manager.repository.getObligationPaymentsByBudget(budgetId))
            currentList = list
            updateSummary(list)
            applyKindFilter(currentKindFilter)
        }
    }

    private fun applyKindFilter(kindFilter: String) {
        currentKindFilter = kindFilter
        kindFilterViews.forEach { (kind, view) ->
            view.isSelected = kind == kindFilter
        }
        val filtered = PlannedObligationHelper.filterByKind(currentList, kindFilter)
        adapter.submit(filtered)
        val emptyView = findViewById<TextView>(R.id.obligationsEmptyState)
        when {
            currentList.isEmpty() -> {
                emptyView.visibility = View.VISIBLE
                emptyView.setText(R.string.obligations_empty)
            }
            filtered.isEmpty() -> {
                emptyView.visibility = View.VISIBLE
                emptyView.setText(R.string.obligations_empty_filter)
            }
            else -> emptyView.visibility = View.GONE
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
        val unlinked = PlannedObligationHelper.unlinkedCount(list)
        val paychecks = ObligationPreferences.getPaychecksPerMonth(this)
        findViewById<TextView>(R.id.obligationsSummaryMonthly).text =
            getString(R.string.obligations_summary_monthly, MoneyFormat.formatRub(monthly))
        findViewById<TextView>(R.id.obligationsSummaryPerPaycheck).text =
            getString(
                R.string.obligations_summary_per_paycheck_compact,
                MoneyFormat.formatRub(perPaycheck),
                paychecks,
            )
        findViewById<TextView>(R.id.obligationsPaychecksHint).apply {
            if (unlinked > 0) {
                text = getString(R.string.obligations_unlinked_hint, unlinked)
                visibility = View.VISIBLE
            } else {
                visibility = View.GONE
            }
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
                syncDefaultAmounts(showEmptyToast = false, showSuccessToast = false)
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun syncDefaultAmounts(
        showEmptyToast: Boolean = true,
        showSuccessToast: Boolean = true,
        onComplete: (() -> Unit)? = null,
    ) {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                ObligationDefaultAmountsSync.apply(manager.repository, budgetId)
            }
            if (result.updatedCount == 0) {
                if (showEmptyToast) {
                    Toast.makeText(this@PlannedObligationsActivity, R.string.obligations_sync_nothing, Toast.LENGTH_LONG).show()
                }
                onComplete?.invoke()
                return@launch
            }
            manager.getCategoriesAsync(forceReload = true)
            withContext(Dispatchers.Main) {
                if (showSuccessToast) {
                    Toast.makeText(
                        this@PlannedObligationsActivity,
                        getString(R.string.obligations_sync_done, result.updatedCount),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
                reload()
                onComplete?.invoke()
            }
        }
    }

    private fun payObligation(item: PlannedObligationEntity) {
        val dueDate = ObligationPaymentHelper.activePeriodDueDate(item) ?: return
        AlertDialog.Builder(this)
            .setTitle(R.string.reminder_mark_paid_title)
            .setMessage(
                getString(
                    R.string.reminder_mark_paid_msg,
                    item.name,
                    MoneyFormat.format(item.amount),
                ),
            )
            .setPositiveButton(R.string.reminder_mark_paid) { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    val ok = ObligationPaymentHelper.payObligation(
                        manager,
                        item.id,
                        dueDate.toEpochDay(),
                    )
                    withContext(Dispatchers.Main) {
                        if (ok) {
                            Toast.makeText(this@PlannedObligationsActivity, R.string.payment_calendar_paid_done, Toast.LENGTH_SHORT).show()
                            reload()
                        }
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showDeleteDialog(item: PlannedObligationEntity) {
        AlertDialog.Builder(this)
            .setTitle(R.string.obligations_delete_title)
            .setMessage(getString(R.string.obligations_delete_msg, item.name))
            .setPositiveButton(R.string.budget_delete_selected) { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    ObligationLinkedSync.onDeleted(manager.repository, item.id)
                    manager.repository.deletePlannedObligation(item.id)
                    withContext(Dispatchers.Main) {
                        if (item.categoryId > 0) {
                            syncDefaultAmounts(showEmptyToast = false, showSuccessToast = false) {
                                Toast.makeText(
                                    this@PlannedObligationsActivity,
                                    R.string.obligations_deleted_synced,
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        } else {
                            reload()
                        }
                    }
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
        val dueDayLabel = inflate.findViewById<TextView>(R.id.obligationDueDayLabel)
        val dueDaySpinner = inflate.findViewById<Spinner>(R.id.obligationDueDaySpinner)
        val dueMonthLabel = inflate.findViewById<TextView>(R.id.obligationDueMonthLabel)
        val dueMonthSpinner = inflate.findViewById<Spinner>(R.id.obligationDueMonthSpinner)
        val categorySpinner = inflate.findViewById<Spinner>(R.id.obligationCategorySpinner)
        val paychecksSpinner = inflate.findViewById<Spinner>(R.id.obligationPaychecksSpinner)
        val paychecksLabel = inflate.findViewById<TextView>(R.id.obligationPaychecksLabel)
        val incomeSourceSpinner = inflate.findViewById<Spinner>(R.id.obligationIncomeSourceSpinner)
        val previewLine = inflate.findViewById<TextView>(R.id.obligationPreviewLine)
        val remindSwitch = inflate.findViewById<SwitchCompat>(R.id.obligationRemindSwitch)
        val autoPostSwitch = inflate.findViewById<SwitchCompat>(R.id.obligationAutoPostSwitch)
        val kindSpinner = inflate.findViewById<Spinner>(R.id.obligationKindSpinner)

        kindSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            PlannedObligationHelper.OBLIGATION_KINDS.map { PlannedObligationHelper.kindLabel(this, it) },
        )
        periodSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            listOf(getString(R.string.obligations_period_monthly), getString(R.string.obligations_period_yearly)),
        )
        dueDaySpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            dueDaySpinnerLabels(),
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
        incomeSourceSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            listOf(getString(R.string.obligations_income_source_any)) + incomeSources.map { it.name },
        )

        val updatePaychecksVisibility = {
            val linked = incomeSourceSpinner.selectedItemPosition > 0
            paychecksLabel.visibility = if (linked) View.GONE else View.VISIBLE
            paychecksSpinner.visibility = if (linked) View.GONE else View.VISIBLE
        }

        if (existing != null) {
            nameInput.setText(existing.name)
            amountInput.setText(MoneyFormat.format(existing.amount))
            periodSpinner.setSelection(if (existing.periodType == PlannedObligationHelper.PERIOD_YEARLY) 1 else 0)
            dueDaySpinner.setSelection(PlannedObligationHelper.dueDaySpinnerPosition(existing.dueDay))
            dueMonthSpinner.setSelection((existing.dueMonth - 1).coerceIn(0, 11))
            val catIndex = leafCategories.indexOfFirst { it.id == existing.categoryId }
            categorySpinner.setSelection(if (catIndex >= 0) catIndex + 1 else 0)
            paychecksSpinner.setSelection((existing.paychecksPerMonth - 1).coerceIn(0, 3))
            remindSwitch.isChecked = existing.remindEnabled
            autoPostSwitch.isChecked = existing.autoPostEnabled
            kindSpinner.setSelection(PlannedObligationHelper.kindSpinnerPosition(existing.obligationKind))
            val linkedIndex = incomeSources.indexOfFirst { it.id == existing.linkedIncomeSourceId }
            incomeSourceSpinner.setSelection(if (linkedIndex >= 0) linkedIndex + 1 else 0)
        } else {
            paychecksSpinner.setSelection((ObligationPreferences.getPaychecksPerMonth(this) - 1).coerceIn(0, 3))
            dueDaySpinner.setSelection(PlannedObligationHelper.dueDaySpinnerPosition(1))
            remindSwitch.isChecked = true
            kindSpinner.setSelection(PlannedObligationHelper.kindSpinnerPosition(PlannedObligationHelper.KIND_OTHER))
            incomeSourceSpinner.setSelection(0)
        }

        val selectedLinkedSourceId = {
            val pos = incomeSourceSpinner.selectedItemPosition
            if (pos <= 0) null else incomeSources.getOrNull(pos - 1)?.id
        }

        val updateAutoPostState = {
            val yearly = periodSpinner.selectedItemPosition == 1
            autoPostSwitch.isEnabled = !yearly
            if (yearly) autoPostSwitch.isChecked = false
        }

        val updatePreview = {
            updateDueDateVisibility(periodSpinner, dueDayLabel, dueDaySpinner, dueMonthLabel, dueMonthSpinner)
            val amount = MoneyFormat.parse(amountInput.text) ?: 0.0
            if (amount <= 0.0) {
                previewLine.text = ""
            } else {
                val period = if (periodSpinner.selectedItemPosition == 1) {
                    PlannedObligationHelper.PERIOD_YEARLY
                } else {
                    PlannedObligationHelper.PERIOD_MONTHLY
                }
                val paychecks = if (incomeSourceSpinner.selectedItemPosition > 0) {
                    1
                } else {
                    paychecksSpinner.selectedItemPosition + 1
                }
                val linkedSourceId = selectedLinkedSourceId()
                val draft = PlannedObligationEntity(
                    budgetId = 0,
                    name = "",
                    amount = amount,
                    periodType = period,
                    categoryId = 0,
                    paychecksPerMonth = paychecks,
                    dueMonth = 1,
                    dueDay = 1,
                    linkedIncomeSourceId = linkedSourceId,
                )
                val per = PlannedObligationHelper.perPaycheck(draft)
                previewLine.text = if (linkedSourceId != null) {
                    val sourceName = incomeSources.firstOrNull { it.id == linkedSourceId }?.name.orEmpty()
                    getString(R.string.obligations_preview_linked, MoneyFormat.formatRub(per), sourceName)
                } else {
                    getString(R.string.obligations_preview, MoneyFormat.formatRub(per), paychecks)
                }
            }
        }
        periodSpinner.onItemSelectedListener = simpleItemSelected {
            updatePreview()
            updateAutoPostState()
        }
        paychecksSpinner.onItemSelectedListener = simpleItemSelected { updatePreview() }
        incomeSourceSpinner.onItemSelectedListener = simpleItemSelected {
            updatePaychecksVisibility()
            updatePreview()
        }
        amountInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) { updatePreview() }
        })
        updatePreview()
        updateAutoPostState()
        updatePaychecksVisibility()

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
                val isYearly = periodSpinner.selectedItemPosition == 1
                val linkedSourceId = selectedLinkedSourceId()
                val paychecks = if (linkedSourceId != null) 1 else paychecksSpinner.selectedItemPosition + 1
                val entity = PlannedObligationEntity(
                    id = existing?.id ?: 0,
                    budgetId = budgetId,
                    name = name,
                    amount = amount,
                    periodType = period,
                    categoryId = categoryId,
                    paychecksPerMonth = paychecks,
                    dueMonth = if (isYearly) dueMonthSpinner.selectedItemPosition + 1 else 1,
                    dueDay = PlannedObligationHelper.dueDayFromSpinnerPosition(dueDaySpinner.selectedItemPosition),
                    createdAt = existing?.createdAt ?: System.currentTimeMillis(),
                    remindEnabled = remindSwitch.isChecked && categoryId > 0,
                    autoPostEnabled = autoPostSwitch.isChecked && categoryId > 0,
                    obligationKind = PlannedObligationHelper.kindFromSpinnerPosition(kindSpinner.selectedItemPosition),
                    linkedIncomeSourceId = linkedSourceId,
                )
                lifecycleScope.launch(Dispatchers.IO) {
                    val savedId = if (existing == null) {
                        manager.repository.insertPlannedObligation(entity).toInt()
                    } else {
                        manager.repository.updatePlannedObligation(entity)
                        entity.id
                    }
                    ObligationLinkedSync.sync(manager.repository, entity.copy(id = savedId))
                    withContext(Dispatchers.Main) {
                        if (entity.categoryId > 0) {
                            syncDefaultAmounts(showEmptyToast = false, showSuccessToast = false) {
                                Toast.makeText(
                                    this@PlannedObligationsActivity,
                                    R.string.obligations_saved_synced,
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        } else {
                            reload()
                        }
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun dueDaySpinnerLabels(): List<String> =
        (1..31).map { getString(R.string.obligations_due_day_number, it) } +
            getString(R.string.obligations_due_day_last)

    private fun updateDueDateVisibility(
        periodSpinner: Spinner,
        dueDayLabel: TextView,
        dueDaySpinner: Spinner,
        dueMonthLabel: TextView,
        dueMonthSpinner: Spinner,
    ) {
        val yearly = periodSpinner.selectedItemPosition == 1
        dueDayLabel.visibility = View.VISIBLE
        dueDaySpinner.visibility = View.VISIBLE
        val monthVis = if (yearly) View.VISIBLE else View.GONE
        dueMonthLabel.visibility = monthVis
        dueMonthSpinner.visibility = monthVis
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
        private val incomeSourceName: (Int?) -> String?,
        private val paidPeriods: () -> Set<ObligationPaymentHelper.PeriodKey>,
        private val onEdit: (PlannedObligationEntity) -> Unit,
        private val onDelete: (PlannedObligationEntity) -> Unit,
        private val onPay: (PlannedObligationEntity) -> Unit,
    ) : RecyclerView.Adapter<ObligationAdapter.Holder>() {
        private var items: List<PlannedObligationEntity> = emptyList()

        fun submit(list: List<PlannedObligationEntity>) {
            items = list
            notifyDataSetChanged()
        }

        class Holder(v: View) : RecyclerView.ViewHolder(v) {
            val name: TextView = v.findViewById(R.id.obligationName)
            val kindBadge: TextView = v.findViewById(R.id.obligationKindBadge)
            val badge: TextView = v.findViewById(R.id.obligationPeriodBadge)
            val amountLine: TextView = v.findViewById(R.id.obligationAmountLine)
            val perPaycheckLine: TextView = v.findViewById(R.id.obligationPerPaycheckLine)
            val categoryLine: TextView = v.findViewById(R.id.obligationCategoryLine)
            val dueLine: TextView = v.findViewById(R.id.obligationDueLine)
            val flagsLine: TextView = v.findViewById(R.id.obligationFlagsLine)
            val paidLine: TextView = v.findViewById(R.id.obligationPaidLine)
            val payButton: com.google.android.material.button.MaterialButton =
                v.findViewById(R.id.obligationPayButton)
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
            holder.kindBadge.text = PlannedObligationHelper.kindLabel(ctx, item.obligationKind)
            holder.badge.text = ctx.getString(
                if (isYearly) R.string.obligations_period_yearly_short else R.string.obligations_period_monthly_short,
            )
            holder.amountLine.text = ctx.getString(
                if (isYearly) R.string.obligations_amount_yearly else R.string.obligations_amount_monthly,
                MoneyFormat.formatRub(item.amount),
            )
            val per = PlannedObligationHelper.perPaycheck(item)
            val linkedName = incomeSourceName(item.linkedIncomeSourceId)
            holder.perPaycheckLine.text = if (!linkedName.isNullOrBlank()) {
                ctx.getString(R.string.obligations_item_linked_paycheck, MoneyFormat.formatRub(per), linkedName)
            } else {
                ctx.getString(
                    R.string.obligations_item_per_paycheck,
                    MoneyFormat.formatRub(per),
                    item.paychecksPerMonth,
                )
            }
            holder.categoryLine.text = ctx.getString(R.string.obligations_item_category, categoryName(item.categoryId))
            holder.dueLine.visibility = View.VISIBLE
            holder.dueLine.text = if (isYearly) {
                ctx.getString(
                    R.string.obligations_item_due_yearly,
                    monthNames[(item.dueMonth - 1).coerceIn(0, 11)],
                    PlannedObligationHelper.dueDayLabel(ctx, item.dueDay),
                )
            } else {
                ctx.getString(
                    R.string.obligations_item_due_day,
                    PlannedObligationHelper.dueDayLabel(ctx, item.dueDay),
                )
            }
            val flags = buildList {
                if (item.remindEnabled) add(ctx.getString(R.string.obligations_flags_remind))
                if (item.autoPostEnabled) add(ctx.getString(R.string.obligations_flags_auto))
            }
            if (flags.isEmpty()) {
                holder.flagsLine.visibility = View.GONE
            } else {
                holder.flagsLine.visibility = View.VISIBLE
                holder.flagsLine.text = flags.joinToString(" · ")
            }
            val paid = paidPeriods()
            val canPay = ObligationPaymentHelper.canPayNow(item, paid)
            val isPaid = ObligationPaymentHelper.isPaidForActivePeriod(item, paid)
            holder.payButton.visibility = if (canPay) View.VISIBLE else View.GONE
            holder.paidLine.visibility = if (isPaid) View.VISIBLE else View.GONE
            if (isPaid) {
                holder.paidLine.text = ctx.getString(R.string.obligations_paid_period)
            }
            holder.payButton.setOnClickListener { onPay(item) }
            holder.itemView.setOnClickListener { onEdit(item) }
            holder.itemView.setOnLongClickListener {
                AlertDialog.Builder(ctx)
                    .setTitle(item.name)
                    .setItems(
                        arrayOf(
                            ctx.getString(R.string.obligations_edit),
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

        override fun getItemCount(): Int = items.size
    }
}
