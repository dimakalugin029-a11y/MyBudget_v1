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
import kotlinx.coroutines.launch
import ru.mybudget.app.data.PlannedIncomeSourceEntity
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_income)
        manager = BudgetManager.getInstance(this)
        ScreenHeaderHelper.setup(
            this,
            getString(R.string.budget_add_income_title),
            getString(R.string.main_icon_income),
        )
        findViewById<View>(R.id.incomeHint)?.let {
            ScreenHintHelper.bind(this, it, ScreenHintHelper.Keys.INCOME, R.string.hint_income, showHelpLink = false)
        }
        amountInput = findViewById(R.id.incomeAmount)
        categorySpinner = findViewById(R.id.incomeCategory)
        descriptionInput = findViewById(R.id.incomeDescription)
        selectedBalanceText = findViewById(R.id.selectedBalanceText)
        budgetNameText = findViewById(R.id.transactionBudgetNameText)
        planSuggestions = findViewById(R.id.incomePlanSuggestions)
        planSuggestionChips = findViewById(R.id.incomePlanSuggestionChips)
        findViewById<View>(R.id.transactionBudgetPicker).setOnClickListener {
            BudgetPicker.show(this, onSwitched = { loadCategories() })
        }
        findViewById<View>(R.id.saveIncomeButton).setOnClickListener { save() }
        findViewById<View>(R.id.distributeIncomeButton).setOnClickListener { openDistribution() }
        categorySpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
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
        lifecycleScope.launch {
            manager.getCategoriesAsync()
            val profiles = manager.getBudgetProfilesAsync()
            val activeId = manager.getActiveBudgetId()
            budgetNameText.text = profiles.firstOrNull { it.id == activeId }?.name
                ?: getString(R.string.budget_profiles_default_name)
            val preferredId = intent.getIntExtra(BudgetIntentExtras.CATEGORY_ID, -1)
            val leaves = manager.getCategoriesForExpenses()
            leafOptions = leaves.map { leaf ->
                val parentName = manager.getCategories().firstOrNull { it.id == leaf.parentId }?.name
                LeafOption(leaf, if (parentName.isNullOrBlank()) leaf.name else "$parentName → ${leaf.name}")
            }
            if (leafOptions.isEmpty()) {
                categorySpinner.adapter = ArrayAdapter(
                    this@IncomeActivity,
                    android.R.layout.simple_spinner_dropdown_item,
                    listOf(getString(R.string.error_no_categories)),
                )
                selectedBalanceText.visibility = View.GONE
                planSuggestions.visibility = View.GONE
                return@launch
            }
            categorySpinner.adapter = ArrayAdapter(
                this@IncomeActivity,
                android.R.layout.simple_spinner_dropdown_item,
                leafOptions.map { it.label },
            )
            val index = leafOptions.indexOfFirst { it.category.id == preferredId }.takeIf { it >= 0 } ?: 0
            categorySpinner.setSelection(index)
            updateBalanceHint()
            applyIntentPrefill()
            loadPlanSuggestions(activeId)
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
        if (!amountInput.text.isNullOrBlank()) {
            planSuggestions.visibility = View.GONE
            return
        }
        val sources = manager.repository.getPlannedIncomeSourcesByBudgetOnce(budgetId)
        val suggestions = PlannedIncomeHelper.suggestionsForIncomeEntry(sources)
        if (suggestions.isEmpty()) {
            planSuggestions.visibility = View.GONE
            return
        }
        planSuggestionChips.removeAllViews()
        suggestions.forEach { source ->
            val chip = TextView(this).apply {
                text = getString(
                    R.string.income_plan_suggestion_chip,
                    source.name,
                    MoneyFormat.formatRub(source.amount),
                )
                setTextAppearance(R.style.Chip_MyBudget_Filter)
                setOnClickListener { applyPlanSuggestion(source) }
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

    private fun applyPlanSuggestion(source: PlannedIncomeSourceEntity) {
        amountInput.setText(MoneyFormat.format(source.amount))
        if (descriptionInput.text.isNullOrBlank()) {
            descriptionInput.setText(source.name)
        }
        planSuggestions.visibility = View.GONE
        amountInput.requestFocus()
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
        val amountText = amountInput.text.toString()
        if (amountText.isBlank()) {
            Toast.makeText(this, R.string.income_enter_amount, Toast.LENGTH_SHORT).show()
            return
        }
        val amount = MoneyFormat.parse(amountText)
        if (amount == null || amount <= 0.0) {
            Toast.makeText(this, R.string.error_invalid_amount, Toast.LENGTH_SHORT).show()
            return
        }
        val option = selectedOption()
        if (option == null) {
            Toast.makeText(this, R.string.error_select_leaf_category, Toast.LENGTH_SHORT).show()
            return
        }
        val description = descriptionInput.text.toString().trim().ifBlank {
            getString(R.string.transaction_income)
        }
        lifecycleScope.launch {
            manager.recordTransaction(option.category.id, amount, "income", description)
            val updated = manager.getCategoryById(option.category.id)
            val parentName = manager.getCategories().firstOrNull { it.id == option.category.parentId }?.name
                ?: option.category.name
            Toast.makeText(
                this@IncomeActivity,
                getString(
                    R.string.income_saved_toast,
                    MoneyFormat.format(amount),
                    parentName,
                    option.category.name,
                    MoneyFormat.formatRub(updated?.currentBalance ?: 0.0),
                ),
                Toast.LENGTH_LONG,
            ).show()
            amountInput.text.clear()
            descriptionInput.text.clear()
            planPrefillApplied = false
            loadCategories()
        }
    }

    private fun selectedOption(): LeafOption? = leafOptions.getOrNull(categorySpinner.selectedItemPosition)

    private fun openDistribution() {
        val amount = MoneyFormat.parse(amountInput.text)
        if (amount == null || amount <= 0.0) {
            Toast.makeText(this, R.string.income_enter_total_amount, Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(this, IncomeDistributionActivity::class.java)
            .putExtra(IncomeDistributionActivity.EXTRA_TOTAL_INCOME, amount)
            .putExtra(BudgetIntentExtras.BUDGET_ID, manager.getActiveBudgetId())
        val note = descriptionInput.text.toString().trim()
        if (note.isNotEmpty()) intent.putExtra(IncomeDistributionActivity.EXTRA_INCOME_NOTE, note)
        PendingDistributionPreferences.setPending(this, manager.getActiveBudgetId(), amount, note)
        startActivity(intent)
        amountInput.text.clear()
        descriptionInput.text.clear()
        planPrefillApplied = false
    }

    private data class LeafOption(val category: BudgetCategory, val label: String)
}
