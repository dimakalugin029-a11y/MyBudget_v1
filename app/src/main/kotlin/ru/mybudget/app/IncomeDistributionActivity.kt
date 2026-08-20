package ru.mybudget.app

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.launch
import kotlin.math.abs

class IncomeDistributionActivity : AppCompatActivity() {
    private lateinit var manager: BudgetManager
    private lateinit var adapter: DistributionLinesAdapter
    private lateinit var totalText: TextView
    private lateinit var submitButton: MaterialButton
    private lateinit var emptyText: TextView
    private var totalIncome = 0.0
    private var selectedIds = mutableListOf<Int>()
    private val amounts = mutableMapOf<Int, Double>()
    private var leaves: List<BudgetCategory> = emptyList()
    private var parents: Map<Int, String> = emptyMap()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_income_distribution)
        manager = BudgetManager.getInstance(this)
        totalIncome = intent.getDoubleExtra(EXTRA_TOTAL_INCOME, 0.0)
        ScreenHeaderHelper.setup(this, getString(R.string.transaction_income_distribution), "⚖️")
        findViewById<View>(R.id.incomeDistributionHint)?.let {
            ScreenHintHelper.bind(
                this,
                it,
                ScreenHintHelper.Keys.INCOME_DISTRIBUTION,
                R.string.hint_income_distribution,
                showHelpLink = false,
            )
        }
        totalText = findViewById(R.id.totalAmountText)
        submitButton = findViewById(R.id.distributeButton)
        emptyText = findViewById(R.id.emptyText)
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
            layoutManager = LinearLayoutManager(this@IncomeDistributionActivity)
            this.adapter = this@IncomeDistributionActivity.adapter
        }
        findViewById<View>(R.id.addCategoryButton).setOnClickListener { pickCategories() }
        findViewById<View>(R.id.applyDefaultsButton).setOnClickListener { applyDefaults() }
        findViewById<View>(R.id.manualEntryButton).setOnClickListener {
            findViewById<TextView>(R.id.distributionModeHint).setText(R.string.income_distribution_mode_manual)
        }
        submitButton.setOnClickListener { submit() }
        bindActionRow(R.id.incomeDistributionPlanRow, R.string.income_distribution_by_plan) {
            startActivity(Intent(this, ExpensePlanActivity::class.java))
        }
        bindActionRow(R.id.incomeDistributionObligationsRow, R.string.income_distribution_by_obligations) {
            startActivity(Intent(this, PlannedObligationsActivity::class.java))
        }
        bindActionRow(R.id.incomeDistributionGoalsRow, R.string.income_distribution_by_goals) {
            startActivity(Intent(this, GoalsActivity::class.java))
        }
        bindActionRow(R.id.incomeDistributionRemainderRow, R.string.income_distribution_remainder) {
            showRemainderDialog()
        }
        lifecycleScope.launch {
            manager.getCategoriesAsync()
            leaves = manager.getCategoriesForExpenses()
            parents = manager.getRootCategories().associate { it.id to it.name }
            if (totalIncome <= 0.0) {
                Toast.makeText(this@IncomeDistributionActivity, R.string.income_enter_total_amount, Toast.LENGTH_LONG).show()
            }
            refreshList()
        }
    }

    private fun bindActionRow(includeId: Int, titleRes: Int, onClick: () -> Unit) {
        val row = findViewById<View>(includeId)
        row.findViewById<TextView>(R.id.rowTitle).setText(titleRes)
        row.setOnClickListener { onClick() }
    }

    private fun pickCategories() {
        CategoryMultiPicker.show(this, leaves, parents, selectedIds.toSet()) { picked ->
            selectedIds.addAll(picked.filter { it !in selectedIds })
            refreshList()
        }
    }

    private fun applyDefaults() {
        val withDefaults = leaves.filter { it.defaultIncomeAmount > 0.0 }
        if (withDefaults.isEmpty()) {
            Toast.makeText(this, R.string.income_distribution_no_defaults, Toast.LENGTH_LONG).show()
            return
        }
        selectedIds = withDefaults.map { it.id }.toMutableList()
        amounts.clear()
        withDefaults.forEach { amounts[it.id] = it.defaultIncomeAmount }
        findViewById<TextView>(R.id.distributionModeHint).setText(R.string.income_distribution_mode_defaults)
        refreshList()
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

    private fun distributed(): Double = amounts.values.sum()

    private fun remaining(): Double = totalIncome - distributed()

    private fun updateTotals() {
        val distributed = distributed()
        val remaining = remaining()
        if (totalIncome > 0.0 && remaining > 0.01) {
            totalText.text = getString(R.string.income_distribution_undistributed_warning, MoneyFormat.format(remaining))
            totalText.setTextColor(ContextCompat.getColor(this, R.color.expense_red))
        } else {
            totalText.text = getString(
                R.string.income_distribution_totals,
                MoneyFormat.format(distributed),
                MoneyFormat.format(maxOf(remaining, 0.0)),
            )
            totalText.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
        }
        submitButton.isEnabled = selectedIds.isNotEmpty() &&
            distributed > 0.0 &&
            (totalIncome <= 0.0 || distributed <= totalIncome + 0.01)
    }

    private fun showRemainderDialog() {
        val leftover = remaining()
        if (leftover <= 0.01) {
            Toast.makeText(this, R.string.income_distribution_no_remainder, Toast.LENGTH_SHORT).show()
            return
        }
        val visible = selectedIds.mapNotNull { id -> leaves.firstOrNull { it.id == id } }
        if (visible.isEmpty()) {
            Toast.makeText(this, R.string.distribution_add_categories_hint, Toast.LENGTH_SHORT).show()
            return
        }
        val labels = visible.map { CategoryMultiPicker.leafLabel(it, parents) }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.income_distribution_remainder_title)
            .setMessage(getString(R.string.income_distribution_remainder_msg, MoneyFormat.format(leftover)))
            .setItems(labels) { _, which ->
                val category = visible[which]
                amounts[category.id] = MoneyFormat.roundMoney((amounts[category.id] ?: 0.0) + leftover)
                refreshList()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun submit() {
        val items = amounts.filter { it.value > 0.0 }.map { it.key to it.value }
        if (items.isEmpty()) {
            Toast.makeText(this, R.string.income_distribution_empty, Toast.LENGTH_SHORT).show()
            return
        }
        if (totalIncome > 0.0 && abs(remaining()) > 0.01 && remaining() < -0.01) {
            Toast.makeText(this, R.string.income_distribution_over_limit, Toast.LENGTH_SHORT).show()
            return
        }
        val note = intent.getStringExtra(EXTRA_INCOME_NOTE)?.trim().orEmpty()
            .ifBlank { getString(R.string.transaction_income_distribution) }
        lifecycleScope.launch {
            manager.applyTransactionGroup(items, "income", note)
            Toast.makeText(this@IncomeDistributionActivity, R.string.income_distribution_done, Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    companion object {
        const val EXTRA_INCOME_NOTE = "income_note"
        const val EXTRA_TOTAL_INCOME = "total_income"
    }
}
