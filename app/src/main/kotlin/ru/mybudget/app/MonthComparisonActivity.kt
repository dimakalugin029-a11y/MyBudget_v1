package ru.mybudget.app

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MonthComparisonActivity : AppCompatActivity() {
    private lateinit var manager: BudgetManager
    private lateinit var adapter: CompareAdapter
    private var monthOptions: List<Pair<Int, Int>> = emptyList()
    private var monthLabels: List<String> = emptyList()
    private var allTransactions: List<ru.mybudget.app.data.TransactionEntity> = emptyList()
    private var categories: List<BudgetCategory> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_month_comparison)
        manager = BudgetManager.getInstance(this)
        ScreenHeaderHelper.setup(
            this,
            getString(R.string.month_comparison_title),
            getString(R.string.main_icon_statistics),
        )
        adapter = CompareAdapter()
        findViewById<RecyclerView>(R.id.monthCompareList).apply {
            layoutManager = LinearLayoutManager(this@MonthComparisonActivity)
            this.adapter = this@MonthComparisonActivity.adapter
        }
        loadData()
    }

    private fun loadData() {
        lifecycleScope.launch {
            val data = withContext(Dispatchers.IO) {
                val txs = manager.repository.getAllTransactions().first()
                val cats = manager.getCategoriesAsync()
                Triple(txs, cats, MonthBudgetComparisonHelper.recentMonths())
            }
            allTransactions = data.first
            categories = data.second
            monthOptions = data.third
            monthLabels = monthOptions.map { (year, month) ->
                MonthBudgetComparisonHelper.monthLabel(year, month)
            }
            val spinnerA = findViewById<Spinner>(R.id.monthCompareSpinnerA)
            val spinnerB = findViewById<Spinner>(R.id.monthCompareSpinnerB)
            val spinnerAdapter = ArrayAdapter(
                this@MonthComparisonActivity,
                android.R.layout.simple_spinner_dropdown_item,
                monthLabels,
            )
            spinnerA.adapter = spinnerAdapter
            spinnerB.adapter = spinnerAdapter
            spinnerA.setSelection(0, false)
            spinnerB.setSelection(minOf(1, monthLabels.lastIndex), false)
            val listener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    refreshComparison()
                }
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
            spinnerA.onItemSelectedListener = listener
            spinnerB.onItemSelectedListener = listener
            refreshComparison()
        }
    }

    private fun refreshComparison() {
        if (monthOptions.isEmpty()) return
        val posA = findViewById<Spinner>(R.id.monthCompareSpinnerA).selectedItemPosition.coerceAtLeast(0)
        val posB = findViewById<Spinner>(R.id.monthCompareSpinnerB).selectedItemPosition.coerceAtLeast(0)
        val (yearA, monthA) = monthOptions[posA]
        val (yearB, monthB) = monthOptions[posB]
        val rangeA = MonthBudgetComparisonHelper.monthRangeMs(yearA, monthA)
        val rangeB = MonthBudgetComparisonHelper.monthRangeMs(yearB, monthB)
        val snapshotA = MonthBudgetComparisonHelper.buildSnapshot(
            yearA, monthA, allTransactions, categories, rangeA.first, rangeA.second,
        )
        val snapshotB = MonthBudgetComparisonHelper.buildSnapshot(
            yearB, monthB, allTransactions, categories, rangeB.first, rangeB.second,
        )
        val comparison = MonthBudgetComparisonHelper.compare(snapshotA, snapshotB)
        bindSummary(snapshotA, snapshotB, comparison)
        adapter.submit(buildRows(snapshotA, snapshotB))
    }

    private fun bindSummary(
        snapshotA: MonthBudgetComparisonHelper.MonthSnapshot,
        snapshotB: MonthBudgetComparisonHelper.MonthSnapshot,
        comparison: MonthBudgetComparisonHelper.ComparisonResult,
    ) {
        findViewById<TextView>(R.id.monthCompareSummaryA).text = getString(
            R.string.month_comparison_summary,
            snapshotA.label,
            MoneyFormat.formatRub(snapshotA.totals.income),
            MoneyFormat.formatRub(snapshotA.totals.expense),
            MoneyFormat.formatRub(snapshotA.totals.saldo),
        )
        findViewById<TextView>(R.id.monthCompareSummaryB).text = getString(
            R.string.month_comparison_summary,
            snapshotB.label,
            MoneyFormat.formatRub(snapshotB.totals.income),
            MoneyFormat.formatRub(snapshotB.totals.expense),
            MoneyFormat.formatRub(snapshotB.totals.saldo),
        )
        findViewById<TextView>(R.id.monthCompareDelta).text = getString(
            R.string.month_comparison_delta,
            StatisticsPeriodComparisonHelper.formatDeltaAmount(comparison.incomeDelta),
            StatisticsPeriodComparisonHelper.formatDeltaAmount(comparison.expenseDelta),
            StatisticsPeriodComparisonHelper.formatDeltaAmount(comparison.saldoDelta),
        )
    }

    private fun buildRows(
        snapshotA: MonthBudgetComparisonHelper.MonthSnapshot,
        snapshotB: MonthBudgetComparisonHelper.MonthSnapshot,
    ): List<CompareRow> {
        val mapB = snapshotB.topExpenses.associateBy { it.name }
        return snapshotA.topExpenses.map { rowA ->
            val rowB = mapB[rowA.name]
            CompareRow(
                name = rowA.name,
                amountA = rowA.amount,
                amountB = rowB?.amount ?: 0.0,
                categoryIds = rowA.categoryIds,
            )
        }
    }

    private data class CompareRow(
        val name: String,
        val amountA: Double,
        val amountB: Double,
        val categoryIds: List<Int>,
    )

    private class CompareAdapter : RecyclerView.Adapter<CompareAdapter.Holder>() {
        private var items: List<CompareRow> = emptyList()

        fun submit(data: List<CompareRow>) {
            items = data
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_month_comparison_row, parent, false)
            return Holder(view)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount(): Int = items.size

        class Holder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val titleView = itemView.findViewById<TextView>(R.id.monthCompareRowTitle)
            private val amountsView = itemView.findViewById<TextView>(R.id.monthCompareRowAmounts)

            fun bind(row: CompareRow) {
                titleView.text = row.name
                amountsView.text = itemView.context.getString(
                    R.string.month_comparison_amounts,
                    MoneyFormat.formatRub(row.amountA),
                    MoneyFormat.formatRub(row.amountB),
                )
            }
        }
    }
}
