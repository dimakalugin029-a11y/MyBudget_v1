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
import ru.mybudget.app.setup.QuickExpensePreferences

class ExpenseActivity : AppCompatActivity() {
    private lateinit var manager: BudgetManager
    private lateinit var amountInput: EditText
    private lateinit var categorySpinner: Spinner
    private lateinit var descriptionInput: EditText
    private lateinit var selectedBalanceText: TextView
    private lateinit var budgetNameText: TextView
    private var leafOptions: List<LeafOption> = emptyList()
    private var saving = false
    private var requestId = java.util.UUID.randomUUID().toString()
    private var selectedCategoryId = -1
    private var categoriesJob: kotlinx.coroutines.Job? = null
    private var categoriesLoading = false
    private var categoryLoadGeneration = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_expense)
        manager = BudgetManager.getInstance(this)
        requestId = savedInstanceState?.getString("expense_request")
            ?: intent.getStringExtra(BudgetIntentExtras.OPERATION_REQUEST_ID) ?: requestId
        selectedCategoryId = savedInstanceState?.getInt("expense_category", -1)
            ?: intent.getIntExtra(BudgetIntentExtras.CATEGORY_ID, -1)
        ScreenHeaderHelper.setup(
            this,
            getString(R.string.budget_add_expense_title),
        )
        findViewById<View>(R.id.expenseHint)?.let {
            ScreenHintHelper.bind(this, it, ScreenHintHelper.Keys.EXPENSE, R.string.hint_expense, showHelpLink = false)
        }
        amountInput = findViewById(R.id.expenseAmount)
        categorySpinner = findViewById(R.id.expenseCategory)
        descriptionInput = findViewById(R.id.expenseDescription)
        if (savedInstanceState == null) {
            intent.getStringExtra(BudgetIntentExtras.OPERATION_AMOUNT_TEXT)?.let { amountInput.setText(it) }
            intent.getStringExtra(BudgetIntentExtras.OPERATION_DESCRIPTION)?.let { descriptionInput.setText(it) }
        }
        OperationFormUi.bindAmount(this, amountInput) { categorySpinner.performClick() }
        selectedBalanceText = findViewById(R.id.selectedBalanceText)
        budgetNameText = findViewById(R.id.transactionBudgetNameText)
        findViewById<View>(R.id.transactionBudgetPicker).setOnClickListener {
            if (!saving && !categoriesLoading) BudgetPicker.show(this, onSwitched = { selectedCategoryId = -1; loadCategories() })
        }
        findViewById<View>(R.id.saveExpenseButton).setOnClickListener { save() }
        findViewById<View>(R.id.distributeExpenseButton).setOnClickListener { openDistribution() }
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
        findViewById<View>(R.id.saveExpenseButton).isEnabled = false
        findViewById<View>(R.id.distributeExpenseButton).isEnabled = false
        categoriesJob = lifecycleScope.launch {
          try {
            manager.getCategoriesAsync()
            val profiles = manager.getBudgetProfilesAsync()
            val activeId = manager.getActiveBudgetId()
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
                    this@ExpenseActivity,
                    R.layout.item_operation_spinner,
                    listOf(getString(R.string.operation_missing_categories)),
                )
                categorySpinner.isEnabled = false
                selectedBalanceText.visibility = View.GONE
                return@launch
            }
            categorySpinner.adapter = ArrayAdapter(
                this@ExpenseActivity,
                R.layout.item_operation_spinner,
                leafOptions.map { it.label },
            ).apply { setDropDownViewResource(R.layout.item_operation_spinner_dropdown) }
            categorySpinner.isEnabled = true
            val index = leafOptions.indexOfFirst { it.category.id == preferredId }.takeIf { it >= 0 } ?: 0
            categorySpinner.setSelection(index)
            updateBalanceHint()
          } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled
          } catch (_: Exception) {
            leafOptions = emptyList()
            Toast.makeText(this@ExpenseActivity, R.string.operation_categories_error, Toast.LENGTH_LONG).show()
          } finally {
            if (generation == categoryLoadGeneration) {
                categoriesLoading = false
                findViewById<View>(R.id.saveExpenseButton).isEnabled = !saving && leafOptions.isNotEmpty()
                findViewById<View>(R.id.distributeExpenseButton).isEnabled = !saving
            }
          }
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
        if (saving || categoriesLoading) return
        val amount = OperationFormUi.amount(amountInput, R.string.expense_enter_amount) ?: return
        val option = selectedOption()
        if (option == null) {
            Toast.makeText(this, R.string.error_select_leaf_category, Toast.LENGTH_SHORT).show()
            return
        }
        val description = descriptionInput.text.toString().trim().ifBlank {
            getString(R.string.transaction_expense)
        }
        saving = true
        categorySpinner.isEnabled = false
        amountInput.isEnabled = false
        descriptionInput.isEnabled = false
        findViewById<View>(R.id.saveExpenseButton).isEnabled = false
        lifecycleScope.launch {
          try {
            manager.recordTransaction(option.category.id, amount, "expense", description, requestId = requestId)
            QuickExpensePreferences.saveLastExpense(
                this@ExpenseActivity,
                option.category.id,
                amount,
                description,
            )
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
            requestId = java.util.UUID.randomUUID().toString()
            loadCategories()
          } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled
          } catch (error: Exception) {
            Toast.makeText(this@ExpenseActivity, R.string.operation_save_error, Toast.LENGTH_LONG).show()
          } finally {
            saving = false
            categorySpinner.isEnabled = !categoriesLoading && leafOptions.isNotEmpty()
            amountInput.isEnabled = true
            descriptionInput.isEnabled = true
            findViewById<View>(R.id.saveExpenseButton).isEnabled = !categoriesLoading && leafOptions.isNotEmpty()
          }
        }
    }

    private fun selectedOption(): LeafOption? = leafOptions.getOrNull(categorySpinner.selectedItemPosition)

    private fun openDistribution() {
        if (saving || categoriesLoading) return
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
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("expense_request", requestId)
        outState.putInt("expense_category", selectedOption()?.category?.id ?: selectedCategoryId)
        super.onSaveInstanceState(outState)
    }
}
