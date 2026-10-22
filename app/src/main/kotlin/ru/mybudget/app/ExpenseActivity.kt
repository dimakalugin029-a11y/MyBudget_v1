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
import androidx.activity.viewModels
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
    private val moneyModel:MoneySaveViewModel by viewModels()
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
        saving = moneyModel.state.value is MoneySaveViewModel.State.Saving
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
        MoneySaveUi.bind(this,moneyModel,{ saving }) { state ->
            setSaving(state is MoneySaveViewModel.State.Saving)
            when(state) {
                is MoneySaveViewModel.State.Done -> {
                    val result=state.result;val command=result.command
                    if(result.outcome==SingleMoneyCommandExecutor.Outcome.SAVED) Toast.makeText(this,
                        getString(R.string.expense_saved_toast,MoneyFormat.format(command.amount),command.parentName,
                            command.categoryName,MoneyFormat.formatRub(result.categoryBalance)),Toast.LENGTH_LONG).show()
                    else Toast.makeText(this,R.string.money_already_processed,Toast.LENGTH_LONG).show()
                    amountInput.text.clear();descriptionInput.text.clear()
                    requestId=java.util.UUID.randomUUID().toString()
                    moneyModel.acknowledge();loadCategories()
                }
                is MoneySaveViewModel.State.Failed -> { Toast.makeText(this,R.string.operation_save_error,Toast.LENGTH_LONG).show();moneyModel.acknowledge() }
                else -> Unit
            }
        }
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
            manager.getCategoriesAsync(forceReload = true)
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
            categorySpinner.isEnabled = !saving
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
        setSaving(true)
        val parentName=manager.getCategories().firstOrNull { it.id==option.category.parentId }?.name ?: option.category.name
        moneyModel.save(SingleMoneyCommand(requestId,option.category.budgetId,option.category.id,amount,"expense",description,
            categoryName=option.category.name,parentName=parentName))
    }

    private fun setSaving(value:Boolean) {
        saving=value
        categorySpinner.isEnabled=!value && !categoriesLoading && leafOptions.isNotEmpty()
        amountInput.isEnabled=!value;descriptionInput.isEnabled=!value
        findViewById<View>(R.id.saveExpenseButton).isEnabled=!value && !categoriesLoading && leafOptions.isNotEmpty()
        findViewById<View>(R.id.distributeExpenseButton).isEnabled=!value && !categoriesLoading
        findViewById<android.widget.Button>(R.id.saveExpenseButton).setText(if(value) R.string.money_saving else R.string.operation_save_expense)
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
