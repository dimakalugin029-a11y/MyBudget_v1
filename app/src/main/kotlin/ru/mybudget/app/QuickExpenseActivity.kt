package ru.mybudget.app

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.launch
import ru.mybudget.app.setup.ExpenseShortcut
import ru.mybudget.app.setup.ExpenseShortcutPreferences
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_quick_expense)
        manager = BudgetManager.getInstance(this)
        ScreenHeaderHelper.setup(
            this,
            getString(R.string.quick_expense_title),
            getString(R.string.main_icon_expense),
        )
        amountInput = findViewById(R.id.quickExpenseAmount)
        descriptionInput = findViewById(R.id.quickExpenseDescription)
        categoryContainer = findViewById(R.id.quickExpenseCategoryContainer)
        repeatButton = findViewById(R.id.quickExpenseRepeatButton)
        findViewById<View>(R.id.quickExpenseFullFormLink).setOnClickListener {
            startActivity(Intent(this, ExpenseActivity::class.java))
            finish()
        }
        findViewById<MaterialButton>(R.id.quickExpenseSaveButton).setOnClickListener { save() }
        findViewById<MaterialButton>(R.id.quickExpenseSaveShortcutButton).setOnClickListener { saveShortcut() }
        repeatButton.setOnClickListener { applyRepeatLast() }
        amountInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                save()
                true
            } else {
                false
            }
        }
        amountInput.requestFocus()
    }

    override fun onResume() {
        super.onResume()
        loadCategories()
        updateRepeatButton()
    }

    private fun loadCategories() {
        lifecycleScope.launch {
            manager.getCategoriesAsync()
            val leaves = manager.getCategoriesForExpenses()
            if (leaves.isEmpty()) {
                categoryContainer.removeAllViews()
                selectedCategoryId = -1
                Toast.makeText(this@QuickExpenseActivity, R.string.error_no_categories, Toast.LENGTH_LONG).show()
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
            if (categoryOptions.isEmpty()) {
                val fallback = leaves.first()
                categoryOptions = listOf(QuickCategoryOption(fallback, formatCategoryLabel(fallback)))
            }
            selectedCategoryId = categoryOptions.firstOrNull()?.category?.id ?: -1
            renderCategoryChips()
            applyPrefillIfNeeded()
        }
    }

    private fun applyPrefillIfNeeded() {
        if (prefilledApplied) return
        prefilledApplied = true
        val categoryId = intent.getIntExtra(EXTRA_CATEGORY_ID, -1)
        if (categoryId > 0) {
            selectedCategoryId = categoryId
            renderCategoryChips()
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
        val margin = resources.getDimensionPixelSize(R.dimen.space_8)
        for (option in categoryOptions) {
            val chip = TextView(this, null, 0, R.style.Chip_MyBudget_Filter).apply {
                text = option.label
                isSelected = option.category.id == selectedCategoryId
                isClickable = true
                isFocusable = true
                updateChipStyle(this, isSelected)
                setOnClickListener {
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
    }

    private fun updateChipStyle(chip: TextView, selected: Boolean) {
        chip.isSelected = selected
        chip.setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
        chip.setTextColor(
            ContextCompat.getColor(
                this,
                if (selected) R.color.white else R.color.text_primary,
            ),
        )
    }

    private fun updateRepeatButton() {
        val hasRepeat = QuickExpensePreferences.hasRepeatableExpense(this)
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
        val amount = QuickExpensePreferences.getLastAmount(this) ?: return
        val categoryId = QuickExpensePreferences.getLastCategoryId(this)
        amountInput.setText(MoneyFormat.format(amount))
        QuickExpensePreferences.getLastDescription(this)?.let { descriptionInput.setText(it) }
        if (categoryId > 0) {
            selectedCategoryId = categoryId
            renderCategoryChips()
        }
    }

    private fun saveShortcut() {
        val amount = parseAmount() ?: return
        val categoryId = selectedCategoryId.takeIf { it > 0 }
            ?: categoryOptions.firstOrNull()?.category?.id
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
        val amount = parseAmount() ?: return
        val categoryId = selectedCategoryId.takeIf { it > 0 }
            ?: categoryOptions.firstOrNull()?.category?.id
        if (categoryId == null) {
            Toast.makeText(this, R.string.error_select_leaf_category, Toast.LENGTH_SHORT).show()
            return
        }
        val description = descriptionInput.text.toString().trim().ifBlank {
            getString(R.string.transaction_expense)
        }
        lifecycleScope.launch {
            manager.recordTransaction(categoryId, amount, "expense", description)
            QuickExpensePreferences.saveLastExpense(this@QuickExpenseActivity, categoryId, amount, description)
            val category = manager.getCategoryById(categoryId)
            val label = category?.let { formatCategoryLabel(it) } ?: getString(R.string.transaction_expense)
            Toast.makeText(
                this@QuickExpenseActivity,
                getString(R.string.quick_expense_saved, MoneyFormat.format(amount), label),
                Toast.LENGTH_SHORT,
            ).show()
            finish()
        }
    }

    private fun parseAmount(): Double? {
        val amountText = amountInput.text.toString()
        if (amountText.isBlank()) {
            Toast.makeText(this, R.string.expense_enter_amount, Toast.LENGTH_SHORT).show()
            return null
        }
        val amount = MoneyFormat.parse(amountText)
        if (amount == null || amount <= 0.0) {
            Toast.makeText(this, R.string.error_invalid_amount, Toast.LENGTH_SHORT).show()
            return null
        }
        return amount
    }

    private data class QuickCategoryOption(val category: BudgetCategory, val label: String)

    companion object {
        const val EXTRA_CATEGORY_ID = "category_id"
        const val EXTRA_AMOUNT = "amount"
        const val EXTRA_LABEL = "label"
    }
}
