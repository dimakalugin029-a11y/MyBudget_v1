package ru.mybudget.app

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.mybudget.app.data.TransactionEntity

class CategoryTrendsActivity : AppCompatActivity() {
    private data class TrendRow(
        val name: String,
        val categoryIds: List<Int>,
        val values: List<Double>,
        val labels: List<String>,
    )

    private lateinit var manager: BudgetManager
    private lateinit var adapter: TrendAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_category_trends)
        manager = BudgetManager.getInstance(this)
        ScreenHeaderHelper.setup(
            this,
            getString(R.string.category_trends_title),
            getString(R.string.main_icon_statistics),
        )
        adapter = TrendAdapter()
        findViewById<RecyclerView>(R.id.categoryTrendsList).apply {
            layoutManager = LinearLayoutManager(this@CategoryTrendsActivity)
            adapter = this@CategoryTrendsActivity.adapter
        }
        lifecycleScope.launch {
            val rows = withContext(Dispatchers.IO) { loadRows() }
            adapter.submit(rows)
        }
    }

    private suspend fun loadRows(): List<TrendRow> {
        val txs = manager.repository.getAllTransactions().first()
        val categories = manager.getCategoriesAsync()
        val parents = categories.associate { it.id to it.name }
        val leaves = categories.filter { it.isActive && !manager.hasSubcategories(it.id) }
        val months = MonthBudgetComparisonHelper.recentMonths().take(6).reversed()
        val ranges = months.map { (year, month) ->
            MonthBudgetComparisonHelper.monthRangeMs(year, month)
        }
        val labels = months.map { (year, month) ->
            MonthBudgetComparisonHelper.monthLabel(year, month)
        }
        return leaves.mapNotNull { category ->
            val ids = mutableListOf(category.id)
            ids += categories.filter { it.parentId == category.id }.map { it.id }
            val values = ranges.map { (from, to) ->
                txs.filter {
                    it.categoryId in ids && it.type == "expense" && it.date in from until to
                }.sumOf { it.amount }
            }
            if (values.all { it < 0.005 }) return@mapNotNull null
            TrendRow(
                name = CategoryMultiPicker.leafLabel(category, parents),
                categoryIds = ids,
                values = values,
                labels = labels,
            )
        }.sortedByDescending { it.values.lastOrNull() ?: 0.0 }
    }

    private class TrendAdapter : RecyclerView.Adapter<TrendAdapter.Holder>() {
        private var items: List<TrendRow> = emptyList()

        fun submit(data: List<TrendRow>) {
            items = data
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_category_trend_row, parent, false)
            return Holder(view)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount(): Int = items.size

        class Holder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val titleView = itemView.findViewById<TextView>(R.id.trendRowTitle)
            private val amountsView = itemView.findViewById<TextView>(R.id.trendRowAmounts)
            private val deltaView = itemView.findViewById<TextView>(R.id.trendRowDelta)

            fun bind(row: TrendRow) {
                titleView.text = row.name
                amountsView.text = row.values
                    .map { MoneyFormat.formatRub(it) }
                    .joinToString(" · ")
                val current = row.values.lastOrNull() ?: 0.0
                val previous = row.values.dropLast(1).lastOrNull() ?: 0.0
                val delta = current - previous
                val context = itemView.context
                deltaView.text = when {
                    delta > 0.005 -> context.getString(
                        R.string.category_trends_up,
                        MoneyFormat.formatRub(delta),
                    )
                    delta < -0.005 -> context.getString(
                        R.string.category_trends_down,
                        MoneyFormat.formatRub(-delta),
                    )
                    else -> context.getString(R.string.category_trends_flat)
                }
                deltaView.setTextColor(
                    ContextCompat.getColor(
                        context,
                        when {
                            delta > 0.005 -> R.color.expense_red
                            delta < -0.005 -> R.color.income_green
                            else -> R.color.text_secondary
                        },
                    ),
                )
            }
        }
    }
}
