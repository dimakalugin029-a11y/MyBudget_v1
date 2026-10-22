package ru.mybudget.app

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.activity.viewModels
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.launch
import ru.mybudget.app.setup.ExpenseShortcut
import ru.mybudget.app.setup.ExpenseShortcutPreferences
import ru.mybudget.app.setup.ParticipantPreferences
import ru.mybudget.app.setup.QuickExpensePreferences

class QuickExpenseActivity : AppCompatActivity() {
    private lateinit var manager: BudgetManager
    private lateinit var amountInput: EditText
    private lateinit var descriptionInput: EditText
    private lateinit var categoryContainer: LinearLayout
    private lateinit var repeatButton: MaterialButton
    private var categoryOptions: List<QuickCategoryOption> = emptyList()
    private var selectedCategoryId: Int = -1
    private var prefilledApplied = false
    private var availableCategories: List<BudgetCategory> = emptyList()
    private val moneyModel:MoneySaveViewModel by viewModels()
    private var saving = false
    private var requestId = java.util.UUID.randomUUID().toString()
    private var categoriesJob: kotlinx.coroutines.Job? = null
    private var categoriesLoading = false
    private var categoryLoadGeneration = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_quick_expense)
        manager = BudgetManager.getInstance(this)
        saving = moneyModel.state.value is MoneySaveViewModel.State.Saving
        selectedCategoryId = savedInstanceState?.getInt("quick_category", -1) ?: -1
        prefilledApplied = savedInstanceState?.getBoolean("quick_prefill") ?: false
        requestId = savedInstanceState?.getString("quick_request") ?: requestId
        ScreenHeaderHelper.setup(
            this,
            getString(R.string.quick_expense_title),
        )
        amountInput = findViewById(R.id.quickExpenseAmount)
        descriptionInput = findViewById(R.id.quickExpenseDescription)
        categoryContainer = findViewById(R.id.quickExpenseCategoryContainer)
        repeatButton = findViewById(R.id.quickExpenseRepeatButton)
        findViewById<View>(R.id.transactionBudgetPicker).setOnClickListener {
            if (!saving && !categoriesLoading) BudgetPicker.show(this, onSwitched = {
                selectedCategoryId = -1
                loadCategories()
            })
        }
        findViewById<View>(R.id.quickExpensePickCategory).setOnClickListener { chooseCategory() }
        findViewById<View>(R.id.quickExpenseFullFormLink).setOnClickListener {
            if (saving || categoriesLoading) return@setOnClickListener
            startActivity(Intent(this, ExpenseActivity::class.java)
                .putExtra(BudgetIntentExtras.CATEGORY_ID, selectedCategoryId)
                .putExtra(BudgetIntentExtras.OPERATION_AMOUNT_TEXT, amountInput.text.toString())
                .putExtra(BudgetIntentExtras.OPERATION_DESCRIPTION, descriptionInput.text.toString())
                .putExtra(BudgetIntentExtras.OPERATION_REQUEST_ID, requestId))
            finish()
        }
        findViewById<MaterialButton>(R.id.quickExpenseSaveButton).setOnClickListener { save() }
        MoneySaveUi.bind(this,moneyModel,{ saving }) { state ->
            setSaving(state is MoneySaveViewModel.State.Saving)
            when(state) {
                is MoneySaveViewModel.State.Done -> {
                    val result=state.result;val command=result.command
                    if(result.outcome==SingleMoneyCommandExecutor.Outcome.SAVED) Toast.makeText(this,
                        getString(R.string.quick_expense_saved,MoneyFormat.format(command.amount),command.label),Toast.LENGTH_SHORT).show()
                    else Toast.makeText(this,R.string.money_already_processed,Toast.LENGTH_LONG).show()
                    finish()
                }
                is MoneySaveViewModel.State.Failed -> { Toast.makeText(this,R.string.operation_save_error,Toast.LENGTH_LONG).show();moneyModel.acknowledge() }
                else -> Unit
            }
        }
        findViewById<MaterialButton>(R.id.quickExpenseSaveShortcutButton).setOnClickListener { saveShortcut() }
        repeatButton.setOnClickListener { applyRepeatLast() }
        OperationFormUi.bindAmount(this, amountInput) { chooseCategory() }
        amountInput.requestFocus()
    }

    override fun onResume() {
        super.onResume()
        loadCategories()
        updateRepeatButton()
    }

    private fun loadCategories() {
        val generation = ++categoryLoadGeneration
        categoriesJob?.cancel()
        categoriesLoading = true
        findViewById<View>(R.id.quickExpenseSaveButton).isEnabled = false
        categoriesJob = lifecycleScope.launch {
          try {
            manager.getCategoriesAsync(forceReload = true)
            val activeId = manager.getActiveBudgetId()
            val profiles = manager.getBudgetProfilesAsync()
            if (activeId != manager.getActiveBudgetId()) return@launch
            findViewById<TextView>(R.id.transactionBudgetNameText).text = profiles.firstOrNull { it.id == activeId }?.name
                ?: getString(R.string.budget_profiles_default_name)
            val leaves = manager.getCategoriesForExpenses().filter { it.isActive }
            availableCategories = leaves
            if (leaves.isEmpty()) {
                categoryContainer.removeAllViews()
                selectedCategoryId = -1
                findViewById<TextView>(R.id.selectedBalanceText).apply {
                    setText(R.string.operation_missing_categories)
                    visibility = View.VISIBLE
                }
                repeatButton.visibility = View.GONE
                return@launch
            }
            val recentIds = manager.repository.getRecentExpenseCategoryIds(limit = 5)
            val lastUsedId = QuickExpensePreferences.getLastCategoryId(this@QuickExpenseActivity)
            val orderedIds = buildList {
                if (lastUsedId > 0) add(lastUsedId)
                addAll(recentIds.filter { it != lastUsedId })
            }.distinct().take(5)

            categoryOptions = orderedIds.mapNotNull { id ->
                leaves.firstOrNull { it.id == id }?.let { leaf ->
                    QuickCategoryOption(leaf, formatCategoryLabel(leaf))
                }
            }
            if (selectedCategoryId !in leaves.map { it.id }) selectedCategoryId = categoryOptions.firstOrNull()?.category?.id ?: leaves.first().id
            applyPrefillIfNeeded()
            renderCategoryChips()
            updateRepeatButton()
          } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled
          } catch (_: Exception) {
            availableCategories = emptyList()
            Toast.makeText(this@QuickExpenseActivity, R.string.operation_categories_error, Toast.LENGTH_LONG).show()
          } finally {
            if (generation == categoryLoadGeneration) {
                categoriesLoading = false
                findViewById<View>(R.id.quickExpenseSaveButton).isEnabled = !saving && availableCategories.isNotEmpty()
            }
          }
        }
    }

    private fun applyPrefillIfNeeded() {
        if (prefilledApplied) return
        prefilledApplied = true
        val categoryId = intent.getIntExtra(EXTRA_CATEGORY_ID, -1)
        if (categoryId > 0) {
            selectedCategoryId = categoryId.takeIf { id -> availableCategories.any { it.id == id } } ?: -1
        }
        intent.getDoubleExtra(EXTRA_AMOUNT, -1.0).takeIf { it > 0.0 }?.let { amount ->
            amountInput.setText(MoneyFormat.format(amount))
        }
        intent.getStringExtra(EXTRA_LABEL)?.takeIf { it.isNotBlank() }?.let { label ->
            descriptionInput.setText(label)
        }
    }

    private fun formatCategoryLabel(leaf: BudgetCategory): String {
        val parentName = manager.getCategories().firstOrNull { it.id == leaf.parentId }?.name
        return if (parentName.isNullOrBlank()) leaf.name else "$parentName → ${leaf.name}"
    }

    private fun renderCategoryChips() {
        categoryContainer.removeAllViews()
        val selected = availableCategories.firstOrNull { it.id == selectedCategoryId }
        if (selected != null && categoryOptions.none { it.category.id == selectedCategoryId }) {
            categoryOptions = (listOf(QuickCategoryOption(selected, formatCategoryLabel(selected))) + categoryOptions).take(6)
        }
        val margin = resources.getDimensionPixelSize(R.dimen.space_8)
        for (option in categoryOptions) {
            val chip = TextView(this, null, 0, R.style.Chip_MyBudget_Filter).apply {
                text = option.label
                isSelected = option.category.id == selectedCategoryId
                isClickable = true
                isFocusable = true
                updateChipStyle(this, isSelected)
                setOnClickListener {
                    if (saving || categoriesLoading) return@setOnClickListener
                    selectedCategoryId = option.category.id
                    renderCategoryChips()
                }
            }
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                marginEnd = margin
            }
            categoryContainer.addView(chip, params)
        }
        findViewById<TextView>(R.id.selectedBalanceText).apply {
            visibility = View.VISIBLE
            text = if (selected == null) getString(R.string.error_select_leaf_category)
                else getString(R.string.subcategory_balance_hint, MoneyFormat.formatRub(selected.currentBalance))
        }
    }

    private fun chooseCategory() {
        if (saving || categoriesLoading) return
        if (availableCategories.isEmpty()) {
            Toast.makeText(this, R.string.operation_missing_categories, Toast.LENGTH_LONG).show()
            return
        }
        ImeHelper.hideKeyboard(this, amountInput)
        val options = availableCategories.toList()
        AlertDialog.Builder(this)
            .setTitle(R.string.quick_expense_category_label)
            .setSingleChoiceItems(options.map(::formatCategoryLabel).toTypedArray(),
                options.indexOfFirst { it.id == selectedCategoryId }) { dialog, index ->
                val selectedId = options[index].id
                if (!saving && !categoriesLoading && availableCategories.any { it.id == selectedId }) {
                    selectedCategoryId = selectedId
                    renderCategoryChips()
                }
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun updateChipStyle(chip: TextView, selected: Boolean) {
        chip.isSelected = selected
        chip.setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
        chip.setTextColor(
            ContextCompat.getColor(
                this,
                if (selected) R.color.md_theme_onPrimary else R.color.text_primary,
            ),
        )
    }

    private fun updateRepeatButton() {
        val hasRepeat = QuickExpensePreferences.hasRepeatableExpense(this) && availableCategories.any {
            it.id == QuickExpensePreferences.getLastCategoryId(this)
        }
        repeatButton.visibility = if (hasRepeat) View.VISIBLE else View.GONE
        if (!hasRepeat) return
        val amount = QuickExpensePreferences.getLastAmount(this) ?: return
        val description = QuickExpensePreferences.getLastDescription(this).orEmpty()
        repeatButton.text = if (description.isBlank()) {
            getString(R.string.quick_expense_repeat_amount, MoneyFormat.format(amount))
        } else {
            getString(R.string.quick_expense_repeat_full, MoneyFormat.format(amount), description)
        }
    }

    private fun applyRepeatLast() {
        if (saving || categoriesLoading) return
        val amount = QuickExpensePreferences.getLastAmount(this) ?: return
        val categoryId = QuickExpensePreferences.getLastCategoryId(this)
        if (availableCategories.none { it.id == categoryId }) return
        amountInput.setText(MoneyFormat.format(amount))
        QuickExpensePreferences.getLastDescription(this)?.let { descriptionInput.setText(it) }
        if (categoryId > 0) {
            selectedCategoryId = categoryId
            renderCategoryChips()
        }
    }

    private fun saveShortcut() {
        if (saving || categoriesLoading) return
        val amount = parseAmount() ?: return
        val categoryId = selectedCategoryId.takeIf { id -> availableCategories.any { it.id == id } }
            ?: run {
                Toast.makeText(this, R.string.error_select_leaf_category, Toast.LENGTH_SHORT).show()
                return
            }
        val label = descriptionInput.text.toString().trim().ifBlank {
            categoryOptions.firstOrNull { it.category.id == categoryId }?.label ?: getString(R.string.transaction_expense)
        }
        ExpenseShortcutPreferences.upsert(
            this,
            ExpenseShortcut(label = label, categoryId = categoryId, amount = amount),
        )
        Toast.makeText(this, R.string.quick_expense_shortcut_saved, Toast.LENGTH_SHORT).show()
    }

    private fun save() {
        if (saving || categoriesLoading) return
        val amount = parseAmount() ?: return
        val categoryId = selectedCategoryId.takeIf { id -> availableCategories.any { it.id == id } }
        if (categoryId == null) {
            Toast.makeText(this, R.string.error_select_leaf_category, Toast.LENGTH_SHORT).show()
            return
        }
        val description = descriptionInput.text.toString().trim().ifBlank {
            getString(R.string.transaction_expense)
        }
        val category=availableCategories.first { it.id==categoryId }
        setSaving(true)
        moneyModel.save(SingleMoneyCommand(requestId,category.budgetId,categoryId,amount,"expense",description,
            ParticipantPreferences.getDefaultParticipant(this),label=formatCategoryLabel(category)))
    }

    private fun setSaving(value:Boolean) {
        saving=value
        amountInput.isEnabled=!value;descriptionInput.isEnabled=!value
        findViewById<MaterialButton>(R.id.quickExpenseSaveButton).apply {
            isEnabled=!value && !categoriesLoading && availableCategories.isNotEmpty()
            setText(if(value) R.string.money_saving else R.string.operation_save_expense)
        }
    }

    private fun parseAmount(): Double? {
        return OperationFormUi.amount(amountInput, R.string.expense_enter_amount)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("quick_category", selectedCategoryId)
        outState.putBoolean("quick_prefill", prefilledApplied)
        outState.putString("quick_request", requestId)
        super.onSaveInstanceState(outState)
    }

    private data class QuickCategoryOption(val category: BudgetCategory, val label: String)

    companion object {
        const val EXTRA_CATEGORY_ID = "category_id"
        const val EXTRA_AMOUNT = "amount"
        const val EXTRA_LABEL = "label"
    }
}
