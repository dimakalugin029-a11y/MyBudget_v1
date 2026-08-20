package ru.mybudget.app

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class ExpenseActivity : AppCompatActivity() {
    private lateinit var manager: BudgetManager
    private lateinit var amountInput: EditText
    private lateinit var categorySpinner: Spinner
    private lateinit var descriptionInput: EditText
    private lateinit var selectedBalanceText: TextView
    private lateinit var budgetNameText: TextView
    private var leafOptions: List<LeafOption> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_expense)
        manager = BudgetManager.getInstance(this)
        ScreenHeaderHelper.setup(
            this,
            getString(R.string.budget_add_expense_title),
            getString(R.string.main_icon_expense),
        )
        findViewById<View>(R.id.expenseHint)?.let {
            ScreenHintHelper.bind(this, it, ScreenHintHelper.Keys.EXPENSE, R.string.hint_expense, showHelpLink = false)
        }
        amountInput = findViewById(R.id.expenseAmount)
        categorySpinner = findViewById(R.id.expenseCategory)
        descriptionInput = findViewById(R.id.expenseDescription)
        selectedBalanceText = findViewById(R.id.selectedBalanceText)
        budgetNameText = findViewById(R.id.transactionBudgetNameText)
        findViewById<View>(R.id.transactionBudgetPicker).setOnClickListener {
            BudgetPicker.show(this, onSwitched = { loadCategories() })
        }
        findViewById<View>(R.id.saveExpenseButton).setOnClickListener { save() }
        findViewById<View>(R.id.distributeExpenseButton).setOnClickListener { openDistribution() }
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
                    this@ExpenseActivity,
                    android.R.layout.simple_spinner_dropdown_item,
                    listOf(getString(R.string.error_no_categories)),
                )
                selectedBalanceText.visibility = View.GONE
                return@launch
            }
            categorySpinner.adapter = ArrayAdapter(
                this@ExpenseActivity,
                android.R.layout.simple_spinner_dropdown_item,
                leafOptions.map { it.label },
            )
            val index = leafOptions.indexOfFirst { it.category.id == preferredId }.takeIf { it >= 0 } ?: 0
            categorySpinner.setSelection(index)
            updateBalanceHint()
        }
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
            Toast.makeText(this, R.string.expense_enter_amount, Toast.LENGTH_SHORT).show()
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
            getString(R.string.transaction_expense)
        }
        lifecycleScope.launch {
            manager.recordTransaction(option.category.id, amount, "expense", description)
            val updated = manager.getCategoryById(option.category.id)
            val parentName = manager.getCategories().firstOrNull { it.id == option.category.parentId }?.name
                ?: option.category.name
            Toast.makeText(
                this@ExpenseActivity,
                getString(
                    R.string.expense_saved_toast,
                    MoneyFormat.format(amount),
                    parentName,
                    option.category.name,
                    MoneyFormat.formatRub(updated?.currentBalance ?: 0.0),
                ),
                Toast.LENGTH_LONG,
            ).show()
            amountInput.text.clear()
            descriptionInput.text.clear()
            loadCategories()
        }
    }

    private fun selectedOption(): LeafOption? = leafOptions.getOrNull(categorySpinner.selectedItemPosition)

    private fun openDistribution() {
        val intent = Intent(this, ExpenseDistributionActivity::class.java)
            .putExtra(BudgetIntentExtras.BUDGET_ID, manager.getActiveBudgetId())
        MoneyFormat.parse(amountInput.text)?.takeIf { it > 0.0 }?.let {
            intent.putExtra(ExpenseDistributionActivity.EXTRA_TOTAL_EXPENSE, it)
        }
        val note = descriptionInput.text.toString().trim()
        if (note.isNotEmpty()) intent.putExtra(ExpenseDistributionActivity.EXTRA_EXPENSE_NOTE, note)
        startActivity(intent)
        amountInput.text.clear()
        descriptionInput.text.clear()
    }

    private data class LeafOption(val category: BudgetCategory, val label: String)
}
