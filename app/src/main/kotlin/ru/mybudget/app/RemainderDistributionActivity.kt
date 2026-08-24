package ru.mybudget.app

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RemainderDistributionActivity : AppCompatActivity() {
    private lateinit var manager: BudgetManager
    private lateinit var adapter: RemainderAdapter
    private lateinit var totalText: TextView
    private lateinit var sourceInfo: TextView
    private lateinit var emptyText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var distributeButton: MaterialButton

    private var parentId = 0
    private var parentName = ""
    private var availableAmount = 0.0
    private var categories: List<BudgetCategory> = emptyList()
    private val amounts = mutableMapOf<Int, Double>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_remainder_distribution)
        ScreenHeaderHelper.setup(this, getString(R.string.transaction_remainder_distribution), "⚖️")
        manager = BudgetManager.getInstance(this)
        parentId = intent.getIntExtra(EXTRA_SOURCE_CATEGORY_ID, 0)
        parentName = intent.getStringExtra(EXTRA_SOURCE_CATEGORY_NAME).orEmpty()
        availableAmount = intent.getDoubleExtra(EXTRA_AVAILABLE_AMOUNT, 0.0)

        sourceInfo = findViewById(R.id.sourceInfo)
        totalText = findViewById(R.id.totalAmountText)
        emptyText = findViewById(R.id.emptyText)
        progressBar = findViewById(R.id.progressBar)
        distributeButton = findViewById(R.id.distributeButton)
        sourceInfo.text = getString(R.string.remainder_source_info, parentName, MoneyFormat.formatRub(availableAmount))

        adapter = RemainderAdapter { id, value ->
            if (value > 0.0) amounts[id] = MoneyFormat.roundMoney(value) else amounts.remove(id)
            updateTotals()
        }
        findViewById<RecyclerView>(R.id.categoriesRecyclerView).apply {
            layoutManager = LinearLayoutManager(this@RemainderDistributionActivity)
            adapter = this@RemainderDistributionActivity.adapter
        }
        findViewById<MaterialButton>(R.id.fillEqualButton).setOnClickListener { fillEqual() }
        findViewById<MaterialButton>(R.id.fillPlanButton).setOnClickListener { fillByPlan() }
        findViewById<MaterialButton>(R.id.fillDefaultButton).setOnClickListener { fillByDefaultIncome() }
        distributeButton.setOnClickListener { applyDistribution() }
        loadCategories()
    }

    private fun loadCategories() {
        progressBar.visibility = View.VISIBLE
        lifecycleScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                manager.getCategoriesAsync()
                manager.getSubCategories(parentId)
            }
            val liveAvailable = manager.getParentRemainingBalance(parentId)
            if (liveAvailable > 0.0) availableAmount = liveAvailable
            sourceInfo.text = getString(
                R.string.remainder_source_info,
                parentName.ifBlank { manager.getCategories().firstOrNull { it.id == parentId }?.name.orEmpty() },
                MoneyFormat.formatRub(availableAmount),
            )
            categories = loaded
            amounts.clear()
            progressBar.visibility = View.GONE
            emptyText.visibility = if (categories.isEmpty()) View.VISIBLE else View.GONE
            adapter.submit(categories, amounts)
            updateTotals()
        }
    }

    private fun distributed(): Double = amounts.values.sum()

    private fun updateTotals() {
        val distributed = distributed()
        val remaining = availableAmount - distributed
        totalText.text = getString(
            R.string.income_distribution_totals,
            MoneyFormat.format(distributed),
            MoneyFormat.format(remaining),
        )
        distributeButton.isEnabled = distributed > 0.0 && distributed <= availableAmount + 0.01
    }

    private fun fillEqual() {
        if (categories.isEmpty() || availableAmount <= 0.0) return
        val part = availableAmount / categories.size
        amounts.clear()
        categories.forEach { amounts[it.id] = MoneyFormat.roundMoney(part) }
        adapter.submit(categories, amounts)
        updateTotals()
    }

    private fun fillByPlan() {
        if (categories.isEmpty() || availableAmount <= 0.0) return
        val totalPlan = categories.sumOf { it.plannedAmount.coerceAtLeast(0.0) }
        if (totalPlan <= 0.0) {
            fillEqual()
            return
        }
        amounts.clear()
        categories.forEach {
            amounts[it.id] = MoneyFormat.roundMoney(availableAmount * (it.plannedAmount.coerceAtLeast(0.0) / totalPlan))
        }
        adapter.submit(categories, amounts)
        updateTotals()
    }

    private fun fillByDefaultIncome() {
        if (categories.isEmpty() || availableAmount <= 0.0) return
        val totalDefault = categories.sumOf { it.defaultIncomeAmount.coerceAtLeast(0.0) }
        if (totalDefault <= 0.0) {
            fillEqual()
            return
        }
        amounts.clear()
        categories.forEach {
            amounts[it.id] = MoneyFormat.roundMoney(
                availableAmount * (it.defaultIncomeAmount.coerceAtLeast(0.0) / totalDefault),
            )
        }
        adapter.submit(categories, amounts)
        updateTotals()
    }

    private fun applyDistribution() {
        val items = amounts.filter { it.value > 0.0 }.map { it.key to it.value }
        val total = items.sumOf { it.second }
        if (items.isEmpty() || total <= 0.0 || total > availableAmount + 0.01) {
            Toast.makeText(this, R.string.remainder_invalid_amount, Toast.LENGTH_SHORT).show()
            return
        }
        progressBar.visibility = View.VISIBLE
        distributeButton.isEnabled = false
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                manager.distributeParentRemainder(
                    parentId,
                    items,
                    getString(R.string.transaction_remainder_distribution),
                )
            }
            progressBar.visibility = View.GONE
            if (ok) {
                Toast.makeText(this@RemainderDistributionActivity, R.string.remainder_done, Toast.LENGTH_SHORT).show()
                finish()
            } else {
                distributeButton.isEnabled = true
                Toast.makeText(this@RemainderDistributionActivity, R.string.remainder_insufficient, Toast.LENGTH_LONG).show()
            }
        }
    }

    private class RemainderAdapter(
        private val onAmountChanged: (Int, Double) -> Unit,
    ) : RecyclerView.Adapter<RemainderAdapter.Holder>() {
        private var items: List<BudgetCategory> = emptyList()
        private var amounts: Map<Int, Double> = emptyMap()

        fun submit(data: List<BudgetCategory>, values: Map<Int, Double>) {
            items = data
            amounts = values.toMap()
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_income_distribution_simple, parent, false)
            return Holder(view)
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.bind(items[position], amounts[items[position].id] ?: 0.0)
        }

        inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
            private val name: TextView = view.findViewById(R.id.categoryName)
            private val hint: TextView? = view.findViewById(R.id.subBalanceText)
            private val amount: EditText = view.findViewById(R.id.amountInput)
            private var boundId = 0
            private val watcher = object : TextWatcher {
                override fun afterTextChanged(s: Editable?) {
                    if (boundId == 0) return
                    onAmountChanged(boundId, MoneyFormat.parse(s) ?: 0.0)
                }
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            }

            init {
                amount.addTextChangedListener(watcher)
            }

            fun bind(category: BudgetCategory, value: Double) {
                boundId = 0
                name.text = category.name
                hint?.text = hint?.context?.getString(
                    R.string.subcategory_balance_hint,
                    MoneyFormat.formatRub(category.currentBalance),
                )
                val shown = if (value > 0.0) MoneyFormat.format(value) else ""
                if (amount.text.toString() != shown) {
                    amount.setText(shown)
                    amount.setSelection(amount.text.length)
                }
                boundId = category.id
            }
        }
    }

    companion object {
        const val EXTRA_SOURCE_CATEGORY_ID = "source_category_id"
        const val EXTRA_SOURCE_CATEGORY_NAME = "source_category_name"
        const val EXTRA_AVAILABLE_AMOUNT = "available_amount"
    }
}
