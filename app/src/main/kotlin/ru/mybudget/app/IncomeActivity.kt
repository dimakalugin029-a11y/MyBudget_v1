package ru.mybudget.app

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.activity.viewModels
import kotlinx.coroutines.launch
import ru.mybudget.app.setup.PendingDistributionPreferences

class IncomeActivity : AppCompatActivity() {
    private lateinit var manager: BudgetManager
    private lateinit var amountInput: EditText
    private lateinit var categorySpinner: Spinner
    private lateinit var descriptionInput: EditText
    private lateinit var selectedBalanceText: TextView
    private lateinit var budgetNameText: TextView
    private lateinit var planSuggestions: View
    private lateinit var planSuggestionChips: LinearLayout
    private var leafOptions: List<LeafOption> = emptyList()
    private var planPrefillApplied = false
    private var plannedSourceId: Int? = null
    private var plannedMonth = java.time.YearMonth.now()
    private var requestId = java.util.UUID.randomUUID().toString()
    private val moneyModel:MoneySaveViewModel by viewModels()
    private var saving = false
    private var categoriesJob: kotlinx.coroutines.Job? = null
    private var categoriesLoading = false
    private var categoryLoadGeneration = 0
    private var selectedCategoryId = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_income)
        findViewById<android.widget.RadioGroup>(R.id.incomeReceiptStatus).check(if(savedInstanceState?.getBoolean("income_final",true)!=false) R.id.incomeReceiptFinal else R.id.incomeReceiptPartial)
        manager = BudgetManager.getInstance(this)
        saving = moneyModel.state.value is MoneySaveViewModel.State.Saving
        plannedSourceId = if (savedInstanceState != null) savedInstanceState.getInt("plan_source", 0).takeIf { it > 0 }
            else intent.getIntExtra(BudgetIntentExtras.PLANNED_INCOME_SOURCE_ID, 0).takeIf { it > 0 }
        plannedMonth = runCatching { java.time.YearMonth.parse(savedInstanceState?.getString("plan_month")
            ?: intent.getStringExtra(BudgetIntentExtras.PLANNED_INCOME_MONTH)) }.getOrDefault(java.time.YearMonth.now())
        requestId = savedInstanceState?.getString("receipt_request") ?: requestId
        planPrefillApplied = savedInstanceState?.getBoolean("income_prefill") ?: false
        selectedCategoryId = savedInstanceState?.getInt("income_category", -1)
            ?: intent.getIntExtra(BudgetIntentExtras.CATEGORY_ID, -1)
        ScreenHeaderHelper.setup(
            this,
            getString(R.string.budget_add_income_title),
        )
        findViewById<View>(R.id.incomeHint)?.let {
            ScreenHintHelper.bind(this, it, ScreenHintHelper.Keys.INCOME, R.string.hint_income, showHelpLink = false)
        }
        amountInput = findViewById(R.id.incomeAmount)
        categorySpinner = findViewById(R.id.incomeCategory)
        descriptionInput = findViewById(R.id.incomeDescription)
        OperationFormUi.bindAmount(this, amountInput) { categorySpinner.performClick() }
        selectedBalanceText = findViewById(R.id.selectedBalanceText)
        budgetNameText = findViewById(R.id.transactionBudgetNameText)
        planSuggestions = findViewById(R.id.incomePlanSuggestions)
        planSuggestionChips = findViewById(R.id.incomePlanSuggestionChips)
        findViewById<View>(R.id.incomePlanSourcePicker).setOnClickListener { showSourcePicker() }
        findViewById<View>(R.id.incomePlanUnbind).setOnClickListener {
            if (!saving && !categoriesLoading) { clearPlan(); loadCategories() }
        }
        findViewById<View>(R.id.transactionBudgetPicker).setOnClickListener {
            if (!saving && !categoriesLoading) BudgetPicker.show(this, onSwitched = { selectedCategoryId = -1; clearPlan(); loadCategories() })
        }
        findViewById<View>(R.id.saveIncomeButton).setOnClickListener { save() }
        MoneySaveUi.bind(this,moneyModel,{ saving }) { state ->
            setSaving(state is MoneySaveViewModel.State.Saving)
            when(state) {
                is MoneySaveViewModel.State.Done -> {
                    val result=state.result;val command=result.command
                    when(result.outcome) {
                        SingleMoneyCommandExecutor.Outcome.SAVED -> Toast.makeText(this,
                            getString(R.string.income_saved_toast,MoneyFormat.format(command.amount),command.parentName,
                                command.categoryName,MoneyFormat.formatRub(result.categoryBalance)),Toast.LENGTH_LONG).show()
                        SingleMoneyCommandExecutor.Outcome.PLAN_CHANGED -> Toast.makeText(this,R.string.income_plan_changed,Toast.LENGTH_LONG).show()
                        SingleMoneyCommandExecutor.Outcome.ALREADY_PROCESSED -> Toast.makeText(this,R.string.money_already_processed,Toast.LENGTH_LONG).show()
                    }
                    amountInput.text.clear();descriptionInput.text.clear();planPrefillApplied=false
                    requestId=java.util.UUID.randomUUID().toString()
                    intent.removeExtra(BudgetIntentExtras.PLANNED_INCOME_AMOUNT)
                    intent.removeExtra(BudgetIntentExtras.PLANNED_INCOME_NAME)
                    moneyModel.acknowledge();loadCategories()
                }
                is MoneySaveViewModel.State.Failed -> { Toast.makeText(this,getString(R.string.income_plan_error,state.message),Toast.LENGTH_LONG).show();moneyModel.acknowledge() }
                else -> Unit
            }
        }
        findViewById<View>(R.id.distributeIncomeButton).setOnClickListener { openDistribution() }
        findViewById<View>(R.id.coverOverspendIncomeButton).setOnClickListener { openDistribution(coverOverspend = true) }
        categorySpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                selectedCategoryId = leafOptions.getOrNull(position)?.category?.id ?: -1
                updateBalanceHint()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    override fun onResume() {
        super.onResume()
        loadCategories()
    }

    private fun loadCategories() {
        val generation = ++categoryLoadGeneration
        categoriesJob?.cancel()
        categoriesLoading = true
        findViewById<View>(R.id.saveIncomeButton).isEnabled = false
        findViewById<View>(R.id.distributeIncomeButton).isEnabled = false
        findViewById<View>(R.id.coverOverspendIncomeButton).isEnabled = false
        categoriesJob = lifecycleScope.launch {
          try {
            manager.getCategoriesAsync(forceReload = true)
            val profiles = manager.getBudgetProfilesAsync()
            val activeId = manager.getActiveBudgetId()
            val boundSource = plannedSourceId?.let { manager.database.budgetDao().getIncomeSourceById(it) }
            if (plannedSourceId != null && (boundSource == null || !boundSource.isActive || boundSource.budgetId != activeId)) clearPlan()
            budgetNameText.text = profiles.firstOrNull { it.id == activeId }?.name
                ?: getString(R.string.budget_profiles_default_name)
            val preferredId = selectedCategoryId
            val leaves = manager.getCategoriesForExpenses().filter { it.isActive }
            leafOptions = leaves.map { leaf ->
                val parentName = manager.getCategories().firstOrNull { it.id == leaf.parentId }?.name
                LeafOption(leaf, if (parentName.isNullOrBlank()) leaf.name else "$parentName → ${leaf.name}")
            }
            if (leafOptions.isEmpty()) {
                categorySpinner.adapter = ArrayAdapter(
                    this@IncomeActivity,
                    R.layout.item_operation_spinner,
                    listOf(getString(R.string.operation_missing_categories)),
                )
                categorySpinner.isEnabled = false
                selectedBalanceText.visibility = View.GONE
                planSuggestions.visibility = View.GONE
                return@launch
            }
            categorySpinner.adapter = ArrayAdapter(
                this@IncomeActivity,
                R.layout.item_operation_spinner,
                leafOptions.map { it.label },
            ).apply { setDropDownViewResource(R.layout.item_operation_spinner_dropdown) }
            categorySpinner.isEnabled = !saving
            val index = leafOptions.indexOfFirst { it.category.id == preferredId }.takeIf { it >= 0 } ?: 0
            categorySpinner.setSelection(index)
            updateBalanceHint()
            applyIntentPrefill()
            updatePlanBinding()
            loadPlanSuggestions(activeId)
          } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled
          } catch (_: Exception) {
            leafOptions = emptyList()
            Toast.makeText(this@IncomeActivity, R.string.operation_categories_error, Toast.LENGTH_LONG).show()
          } finally {
            if (generation == categoryLoadGeneration) {
                categoriesLoading = false
                findViewById<View>(R.id.saveIncomeButton).isEnabled = !saving && leafOptions.isNotEmpty()
                findViewById<View>(R.id.distributeIncomeButton).isEnabled = !saving
                findViewById<View>(R.id.coverOverspendIncomeButton).isEnabled = !saving
            }
          }
        }
    }

    private fun applyIntentPrefill() {
        if (planPrefillApplied) return
        val plannedAmount = intent.getDoubleExtra(BudgetIntentExtras.PLANNED_INCOME_AMOUNT, -1.0)
        val plannedName = intent.getStringExtra(BudgetIntentExtras.PLANNED_INCOME_NAME)?.trim().orEmpty()
        if (plannedAmount > 0.0 && amountInput.text.isNullOrBlank()) {
            amountInput.setText(MoneyFormat.format(plannedAmount))
        }
        if (plannedName.isNotEmpty() && descriptionInput.text.isNullOrBlank()) {
            descriptionInput.setText(plannedName)
        }
        if (plannedAmount > 0.0 || plannedName.isNotEmpty()) {
            planPrefillApplied = true
        }
    }

    private suspend fun loadPlanSuggestions(budgetId: Int) {
        if (plannedSourceId != null) { planSuggestions.visibility = View.GONE; return }
        if (!amountInput.text.isNullOrBlank()) {
            planSuggestions.visibility = View.GONE
            return
        }
        val sources = manager.repository.getPlannedIncomeSourcesByBudgetOnce(budgetId)
        val dao = manager.database.budgetDao()
        val overrides = dao.getMonthlyIncomePlansForBudget(budgetId)
        val receipts = dao.getIncomePlanTransactions(budgetId)
        val today = java.time.LocalDate.now()
        val suggestions = sources.filter { it.dayOfMonth > 0 }.flatMap { source ->
            (-1L..1L).mapNotNull { offset ->
                val month = java.time.YearMonth.from(today).plusMonths(offset)
                val date = month.atDay(source.dayOfMonth.coerceIn(1, month.lengthOfMonth()))
                val progress = IncomePlanAmounts.progress(source, month, overrides, receipts)
                if (kotlin.math.abs(date.toEpochDay() - today.toEpochDay()) <= 5 && progress.remaining > 0.0)
                    Triple(source, month, progress.remaining) else null
            }
        }
        if (suggestions.isEmpty()) {
            planSuggestions.visibility = View.GONE
            return
        }
        planSuggestionChips.removeAllViews()
        suggestions.forEach { (source, month, remaining) ->
            val chip = TextView(this).apply {
                text = getString(
                    R.string.income_plan_suggestion_chip,
                    source.name,
                    MoneyFormat.formatRub(remaining),
                )
                setTextAppearance(R.style.Chip_MyBudget_Filter)
                setOnClickListener {
                    if (saving || categoriesLoading) return@setOnClickListener
                    plannedSourceId = source.id
                    plannedMonth = month
                    requestId = java.util.UUID.randomUUID().toString()
                    amountInput.setText(MoneyFormat.format(remaining))
                    if (descriptionInput.text.isNullOrBlank()) descriptionInput.setText(source.name)
                    planSuggestions.visibility = View.GONE
                    lifecycleScope.launch { updatePlanBinding() }
                }
            }
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                marginEnd = resources.getDimensionPixelSize(R.dimen.space_6)
            }
            chip.layoutParams = params
            planSuggestionChips.addView(chip)
        }
        planSuggestions.visibility = View.VISIBLE
    }

    private fun updateBalanceHint() {
        val option = selectedOption() ?: run {
            selectedBalanceText.visibility = View.GONE
            return
        }
        selectedBalanceText.visibility = View.VISIBLE
        selectedBalanceText.text = getString(
            R.string.subcategory_balance_hint,
            MoneyFormat.formatRub(option.category.currentBalance),
        )
    }

    private fun save() {
        if (saving || categoriesLoading) return
        val amount = OperationFormUi.amount(amountInput, R.string.income_enter_amount) ?: return
        val option = selectedOption()
        if (option == null) {
            Toast.makeText(this, R.string.error_select_leaf_category, Toast.LENGTH_SHORT).show()
            return
        }
        val description = descriptionInput.text.toString().trim().ifBlank {
            getString(R.string.transaction_income)
        }
        setSaving(true)
        val parentName=manager.getCategories().firstOrNull { it.id==option.category.parentId }?.name ?: option.category.name
        moneyModel.save(SingleMoneyCommand(requestId,option.category.budgetId,option.category.id,amount,"income",description,
            categoryName=option.category.name,parentName=parentName,plannedSourceId=plannedSourceId,plannedMonth=plannedMonth,finalReceipt=findViewById<android.widget.RadioGroup>(R.id.incomeReceiptStatus).checkedRadioButtonId==R.id.incomeReceiptFinal))
    }

    private fun setSaving(value:Boolean) {
        listOf(R.id.incomeReceiptFinal,R.id.incomeReceiptPartial).forEach { findViewById<View>(it).isEnabled=!value }
        saving=value
        categorySpinner.isEnabled=!value && !categoriesLoading && leafOptions.isNotEmpty()
        amountInput.isEnabled=!value;descriptionInput.isEnabled=!value
        findViewById<android.widget.Button>(R.id.saveIncomeButton).apply {
            isEnabled=!value && !categoriesLoading && leafOptions.isNotEmpty()
            setText(if(value) R.string.money_saving else R.string.operation_save_income)
        }
        findViewById<View>(R.id.distributeIncomeButton).isEnabled=!value && !categoriesLoading
        findViewById<View>(R.id.incomePlanSourcePicker).isEnabled=!value && !categoriesLoading
        findViewById<View>(R.id.coverOverspendIncomeButton).isEnabled=!value && !categoriesLoading
    }

    private fun selectedOption(): LeafOption? = leafOptions.getOrNull(categorySpinner.selectedItemPosition)

    private fun openDistribution(coverOverspend: Boolean = false) {
        if (saving || categoriesLoading) return
        val amount = MoneyFormat.parse(amountInput.text)
        if (amount == null || !amount.isFinite() || amount <= 0.0) {
            Toast.makeText(this, R.string.income_enter_total_amount, Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(this, IncomeDistributionActivity::class.java)
            .putExtra(IncomeDistributionActivity.EXTRA_TOTAL_INCOME, amount)
            .putExtra(IncomeDistributionActivity.EXTRA_OPEN_COVERAGE, coverOverspend)
            .putExtra(IncomeDistributionActivity.EXTRA_RECEIPT_REQUEST, requestId)
            .putExtra("income_final",findViewById<android.widget.RadioGroup>(R.id.incomeReceiptStatus).checkedRadioButtonId==R.id.incomeReceiptFinal)
            .putExtra(BudgetIntentExtras.BUDGET_ID, manager.getActiveBudgetId())
        val note = descriptionInput.text.toString().trim()
        plannedSourceId?.let {
            intent.putExtra(BudgetIntentExtras.PLANNED_INCOME_SOURCE_ID, it)
                .putExtra(BudgetIntentExtras.PLANNED_INCOME_MONTH, plannedMonth.toString())
        }
        if (note.isNotEmpty()) intent.putExtra(IncomeDistributionActivity.EXTRA_INCOME_NOTE, note)
        PendingDistributionPreferences.setPending(this, manager.getActiveBudgetId(), amount, note,
            plannedSourceId, plannedMonth.toString(), requestId,finalReceipt=findViewById<android.widget.RadioGroup>(R.id.incomeReceiptStatus).checkedRadioButtonId==R.id.incomeReceiptFinal)
        startActivity(intent)
        amountInput.text.clear()
        descriptionInput.text.clear()
        planPrefillApplied = false
        clearPlan()
    }

    private fun showSourcePicker() {
        if(saving || categoriesLoading) return
        val active=manager.getActiveBudgetId()
        lifecycleScope.launch {
            try {
                val sources=manager.repository.getPlannedIncomeSourcesByBudgetOnce(active)
                if(active!=manager.getActiveBudgetId() || saving || categoriesLoading) return@launch
                val names=listOf(getString(R.string.income_source_without_plan))+sources.map { it.name }
                androidx.appcompat.app.AlertDialog.Builder(this@IncomeActivity)
                    .setTitle(R.string.income_source_choose)
                    .setItems(names.toTypedArray()) { _,index ->
                        if(saving || categoriesLoading || active!=manager.getActiveBudgetId()) return@setItems
                        if(index==0) { clearPlan();loadCategories() }
                        else {
                            val source=sources[index-1]
                            plannedSourceId=source.id
                            plannedMonth=java.time.YearMonth.now()
                            requestId=java.util.UUID.randomUUID().toString()
                            if(descriptionInput.text.isNullOrBlank()) descriptionInput.setText(source.name)
                            planSuggestions.visibility=View.GONE
                            lifecycleScope.launch { updatePlanBinding() }
                        }
                    }.setNegativeButton(android.R.string.cancel,null).show()
            } catch(cancelled:kotlinx.coroutines.CancellationException) { throw cancelled
            } catch(_:Exception) { Toast.makeText(this@IncomeActivity,R.string.income_link_load_error,Toast.LENGTH_LONG).show() }
        }
    }

    private fun clearPlan() {
        findViewById<View>(R.id.incomeReceiptStatus).visibility=View.GONE
        plannedSourceId = null
        requestId = java.util.UUID.randomUUID().toString()
        intent.removeExtra(BudgetIntentExtras.PLANNED_INCOME_SOURCE_ID)
        intent.removeExtra(BudgetIntentExtras.PLANNED_INCOME_MONTH)
        intent.removeExtra(BudgetIntentExtras.PLANNED_INCOME_AMOUNT)
        intent.removeExtra(BudgetIntentExtras.PLANNED_INCOME_NAME)
        findViewById<View>(R.id.incomePlanBinding).visibility = View.GONE
        findViewById<View>(R.id.incomePlanUnbind).visibility = View.GONE
    }

    private suspend fun updatePlanBinding() {
        val dao = manager.database.budgetDao()
        val source = plannedSourceId?.let { dao.getIncomeSourceById(it) } ?: return
        findViewById<View>(R.id.incomeReceiptStatus).visibility=View.VISIBLE
        val progress = IncomePlanAmounts.progress(source, plannedMonth,
            dao.getMonthlyIncomePlansForMonth(source.budgetId, plannedMonth.year, plannedMonth.monthValue),
            dao.getIncomePlanTransactions(source.budgetId))
        findViewById<TextView>(R.id.incomePlanBinding).apply {
            visibility = View.VISIBLE
            text = getString(R.string.income_actual_binding, source.name, plannedMonth.toString(), MoneyFormat.formatRub(progress.remaining))
        }
        findViewById<View>(R.id.incomePlanUnbind).visibility = View.VISIBLE
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("income_final",findViewById<android.widget.RadioGroup>(R.id.incomeReceiptStatus).checkedRadioButtonId==R.id.incomeReceiptFinal)
        outState.putInt("plan_source", plannedSourceId ?: 0)
        outState.putString("plan_month", plannedMonth.toString())
        outState.putString("receipt_request", requestId)
        outState.putInt("income_category", selectedOption()?.category?.id ?: selectedCategoryId)
        outState.putBoolean("income_prefill", planPrefillApplied)
        super.onSaveInstanceState(outState)
    }

    private data class LeafOption(val category: BudgetCategory, val label: String)
}
