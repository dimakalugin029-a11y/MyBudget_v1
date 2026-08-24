package ru.mybudget.app

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.launch

class ExpenseDistributionActivity : AppCompatActivity() {
    private lateinit var manager: BudgetManager
    private lateinit var adapter: DistributionLinesAdapter
    private lateinit var totalInput: EditText
    private lateinit var noteInput: EditText
    private lateinit var totalText: TextView
    private lateinit var submitButton: MaterialButton
    private lateinit var emptyText: TextView
    private var selectedIds = mutableListOf<Int>()
    private val amounts = mutableMapOf<Int, Double>()
    private var leaves: List<BudgetCategory> = emptyList()
    private var parents: Map<Int, String> = emptyMap()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_expense_distribution)
        manager = BudgetManager.getInstance(this)
        ScreenHeaderHelper.setup(
            this,
            getString(R.string.expense_distribution_title),
            getString(R.string.ui_receipt),
        )
        findViewById<View>(R.id.expenseDistributionHint)?.let {
            ScreenHintHelper.bind(
                this,
                it,
                ScreenHintHelper.Keys.EXPENSE_DISTRIBUTION,
                R.string.hint_expense_distribution,
                showHelpLink = false,
            )
        }
        totalInput = findViewById(R.id.totalExpenseInput)
        noteInput = findViewById(R.id.expenseNoteInput)
        totalText = findViewById(R.id.totalAmountText)
        submitButton = findViewById(R.id.distributeButton)
        emptyText = findViewById(R.id.emptyText)
        val preset = intent.getDoubleExtra(EXTRA_TOTAL_EXPENSE, 0.0)
        if (preset > 0.0) totalInput.setText(MoneyFormat.format(preset))
        intent.getStringExtra(EXTRA_EXPENSE_NOTE)?.takeIf { it.isNotBlank() }?.let { noteInput.setText(it) }
        adapter = DistributionLinesAdapter(
            onAmountChanged = { id, amount ->
                if (amount > 0.0) amounts[id] = MoneyFormat.roundMoney(amount) else amounts.remove(id)
                updateTotals()
            },
            onRemove = { id ->
                selectedIds.remove(id)
                amounts.remove(id)
                refreshList()
            },
        )
        findViewById<RecyclerView>(R.id.categoriesRecyclerView).apply {
            layoutManager = LinearLayoutManager(this@ExpenseDistributionActivity)
            this.adapter = this@ExpenseDistributionActivity.adapter
        }
        findViewById<View>(R.id.addCategoryButton).setOnClickListener { pickCategories() }
        findViewById<View>(R.id.distributeRemainderButton).setOnClickListener { showRemainderDialog() }
        submitButton.setOnClickListener { submit() }
        totalInput.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = updateTotals()
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        })
        lifecycleScope.launch {
            manager.getCategoriesAsync()
            leaves = manager.getCategoriesForExpenses()
            parents = manager.getRootCategories().associate { it.id to it.name }
            applyPrefill()
            refreshList()
        }
    }

    private fun applyPrefill() {
        val ids = intent.getIntArrayExtra(EXTRA_PREFILL_CATEGORY_IDS) ?: return
        val values = intent.getDoubleArrayExtra(EXTRA_PREFILL_AMOUNTS) ?: return
        selectedIds = ids.toMutableList()
        amounts.clear()
        ids.forEachIndexed { index, id ->
            if (index < values.size && values[index] > 0.0) {
                amounts[id] = MoneyFormat.roundMoney(values[index])
            }
        }
    }

    private fun totalAmount(): Double = MoneyFormat.parse(totalInput.text) ?: 0.0

    private fun pickCategories() {
        CategoryMultiPicker.show(this, leaves, parents, selectedIds.toSet()) { picked ->
            selectedIds.addAll(picked.filter { it !in selectedIds })
            refreshList()
        }
    }

    private fun refreshList() {
        val lines = selectedIds.mapNotNull { id ->
            val category = leaves.firstOrNull { it.id == id } ?: return@mapNotNull null
            val amount = amounts[id]
            DistributionLinesAdapter.Line(
                categoryId = id,
                title = CategoryMultiPicker.leafLabel(category, parents),
                balanceHint = getString(R.string.subcategory_balance_hint, MoneyFormat.formatRub(category.currentBalance)),
                amountText = if (amount != null && amount > 0.0) MoneyFormat.format(amount) else "",
            )
        }
        adapter.submit(lines)
        emptyText.visibility = if (lines.isEmpty()) View.VISIBLE else View.GONE
        updateTotals()
    }

    private fun updateTotals() {
        val total = totalAmount()
        val distributed = amounts.values.sum()
        val remaining = total - distributed
        totalText.text = if (total > 0.0) {
            getString(
                R.string.expense_distribution_totals,
                MoneyFormat.format(distributed),
                MoneyFormat.format(maxOf(remaining, 0.0)),
            )
        } else {
            getString(R.string.expense_distribution_totals_default)
        }
        submitButton.isEnabled = total > 0.0 &&
            selectedIds.isNotEmpty() &&
            distributed > 0.0 &&
            distributed <= total + 0.01 &&
            total - distributed <= 0.01
    }

    private fun showRemainderDialog() {
        val leftover = totalAmount() - amounts.values.sum()
        if (leftover <= 0.01) {
            Toast.makeText(
                this,
                if (totalAmount() <= 0.0) R.string.expense_distribution_enter_total else R.string.expense_distribution_no_remainder,
                Toast.LENGTH_SHORT,
            ).show()
            return
        }
        val visible = selectedIds.mapNotNull { id -> leaves.firstOrNull { it.id == id } }
        if (visible.isEmpty()) {
            Toast.makeText(this, R.string.distribution_add_categories_hint, Toast.LENGTH_SHORT).show()
            return
        }
        val labels = visible.map { CategoryMultiPicker.leafLabel(it, parents) }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.expense_distribution_remainder_title)
            .setMessage(getString(R.string.expense_distribution_remainder_msg, MoneyFormat.format(leftover)))
            .setItems(labels) { _, which ->
                val category = visible[which]
                amounts[category.id] = MoneyFormat.roundMoney((amounts[category.id] ?: 0.0) + leftover)
                refreshList()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun submit() {
        if (!submitButton.isEnabled) {
            Toast.makeText(this, R.string.expense_distribution_under_limit, Toast.LENGTH_SHORT).show()
            return
        }
        val items = amounts.filter { it.value > 0.0 }.map { it.key to it.value }
        val note = noteInput.text.toString().trim().ifBlank { getString(R.string.transaction_expense_distribution) }
        lifecycleScope.launch {
            manager.applyTransactionGroup(items, "expense", note)
            Toast.makeText(
                this@ExpenseDistributionActivity,
                getString(R.string.expense_distribution_done, MoneyFormat.format(totalAmount())),
                Toast.LENGTH_SHORT,
            ).show()
            finish()
        }
    }

    companion object {
        const val EXTRA_EXPENSE_NOTE = "expense_note"
        const val EXTRA_TOTAL_EXPENSE = "total_expense"
        const val EXTRA_PREFILL_CATEGORY_IDS = "prefill_category_ids"
        const val EXTRA_PREFILL_AMOUNTS = "prefill_amounts"
    }
}
