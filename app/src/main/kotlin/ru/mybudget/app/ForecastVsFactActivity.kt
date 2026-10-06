package ru.mybudget.app

import android.content.Context
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
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.mybudget.app.data.ForecastSnapshotEntity

class ForecastVsFactActivity : AppCompatActivity() {
    private lateinit var manager: BudgetManager
    private lateinit var adapter: ComparisonAdapter
    private lateinit var emptyView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_forecast_vs_fact)
        manager = BudgetManager.getInstance(this)
        ScreenHeaderHelper.setup(
            this,
            getString(R.string.forecast_vs_fact_title),
            getString(R.string.main_icon_statistics),
        )
        emptyView = findViewById(R.id.forecastVsFactEmpty)
        adapter = ComparisonAdapter()
        findViewById<RecyclerView>(R.id.forecastVsFactList).apply {
            layoutManager = LinearLayoutManager(this@ForecastVsFactActivity)
            adapter = this@ForecastVsFactActivity.adapter
        }
        load()
    }

    private fun load() {
        lifecycleScope.launch {
            val rows = withContext(Dispatchers.IO) {
                val budgetId = manager.getActiveBudgetId()
                val categories = manager.getCategories()
                val categoryIds = categories.map { it.id }.toSet()
                val parents = categories.associate { it.id to it.name }
                val snapshots = manager.repository.getForecastSnapshots(budgetId)
                    .filter { it.year * 12 + it.month < java.time.LocalDate.now().year * 12 + java.time.LocalDate.now().monthValue }
                    .sortedWith(compareByDescending<ForecastSnapshotEntity> { it.year }.thenByDescending { it.month })
                    .groupBy { it.year * 12 + it.month }
                    .map { group ->
                        group.value.firstOrNull { it.scenario == ForecastHelper.Scenario.REALISTIC.name }
                            ?: group.value.first()
                    }
                    .sortedWith(compareByDescending<ForecastSnapshotEntity> { it.year }.thenByDescending { it.month })
                snapshots.map { snapshot ->
                    val range = MonthBudgetComparisonHelper.monthRangeMs(snapshot.year, snapshot.month)
                    val transactions = manager.repository.getTransactionsInRange(range.first, range.second)
                        .filter { it.categoryId in categoryIds }
                    val lines = ForecastSnapshotCodec.decode(snapshot.linesJson)
                    val factIncomeBySource = transactions
                        .filter { it.type == "income" }
                        .groupBy { it.categoryId }
                        .mapKeys { entry ->
                            val sourceName = lines?.incomes?.firstOrNull { it.sourceId == entry.key }?.name
                            sourceName ?: categories.firstOrNull { it.id == entry.key }?.let {
                                CategoryMultiPicker.leafLabel(it, parents)
                            } ?: entry.key.toString()
                        }
                        .mapValues { entry -> entry.value.sumOf { it.amount } }
                    val factExpenseByCategory = transactions
                        .filter { it.type == "expense" }
                        .groupBy { it.categoryId }
                        .mapKeys { entry ->
                            val lineName = lines?.expenses?.firstOrNull { it.categoryId == entry.key }?.name
                            lineName ?: categories.firstOrNull { it.id == entry.key }?.let {
                                CategoryMultiPicker.leafLabel(it, parents)
                            } ?: entry.key.toString()
                        }
                        .mapValues { entry -> entry.value.sumOf { it.amount } }
                    Row(
                        comparison = ForecastVsFactHelper.compareMonth(
                            year = snapshot.year,
                            month = snapshot.month,
                            forecastIncome = snapshot.totalIncome,
                            forecastExpense = snapshot.totalExpense,
                            transactions = transactions,
                        ),
                        incomeLines = lines?.let {
                            ForecastVsFactHelper.compareLines(
                                it.incomes.map { line -> line.name to line.amount },
                                factIncomeBySource,
                            )
                        } ?: emptyList(),
                        expenseLines = lines?.let {
                            ForecastVsFactHelper.compareLines(
                                it.expenses.map { line -> line.name to line.amount },
                                factExpenseByCategory,
                            )
                        } ?: emptyList(),
                    )
                }
            }
            adapter.submit(rows)
            emptyView.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private data class Row(
        val comparison: ForecastVsFactHelper.MonthComparison,
        val incomeLines: List<ForecastVsFactHelper.LineComparison>,
        val expenseLines: List<ForecastVsFactHelper.LineComparison>,
    )

    private class ComparisonAdapter : RecyclerView.Adapter<ComparisonAdapter.Holder>() {
        private var items: List<Row> = emptyList()
        private val expanded = mutableSetOf<Int>()

        fun submit(data: List<Row>) {
            items = data
            expanded.clear()
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_forecast_vs_fact, parent, false)
            return Holder(view)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.bind(items[position], expanded.contains(position)) { toggle(position) }
        }

        override fun getItemCount(): Int = items.size

        private fun toggle(position: Int) {
            if (!expanded.add(position)) expanded.remove(position)
            notifyItemChanged(position)
        }

        class Holder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val titleView = itemView.findViewById<TextView>(R.id.forecastVsFactMonthTitle)
            private val incomeView = itemView.findViewById<TextView>(R.id.forecastVsFactIncome)
            private val expenseView = itemView.findViewById<TextView>(R.id.forecastVsFactExpense)
            private val netView = itemView.findViewById<TextView>(R.id.forecastVsFactNet)
            private val toggleButton = itemView.findViewById<MaterialButton>(R.id.forecastVsFactToggle)
            private val detailsView = itemView.findViewById<TextView>(R.id.forecastVsFactDetails)

            fun bind(row: Row, expanded: Boolean, onToggle: () -> Unit) {
                val context = itemView.context
                val c = row.comparison
                titleView.text = MonthBudgetComparisonHelper.monthLabel(c.year, c.month)
                incomeView.text = context.getString(
                    R.string.forecast_vs_fact_income_delta,
                    MoneyFormat.formatRub(c.forecastIncome),
                    MoneyFormat.formatRub(c.factIncome),
                    deltaText(context, c.incomeDelta),
                )
                expenseView.text = context.getString(
                    R.string.forecast_vs_fact_expense_delta,
                    MoneyFormat.formatRub(c.forecastExpense),
                    MoneyFormat.formatRub(c.factExpense),
                    deltaText(context, c.expenseDelta),
                )
                netView.text = context.getString(
                    R.string.forecast_vs_fact_net_delta,
                    MoneyFormat.formatRub(c.forecastNet),
                    MoneyFormat.formatRub(c.factNet),
                    deltaText(context, c.netDelta),
                )
                netView.setTextColor(
                    ContextCompat.getColor(
                        context,
                        if (c.netDelta.absolute >= 0.0) R.color.income_green else R.color.expense_red,
                    ),
                )
                val hasLines = row.incomeLines.isNotEmpty() || row.expenseLines.isNotEmpty()
                if (!hasLines) {
                    toggleButton.visibility = View.GONE
                } else {
                    toggleButton.visibility = View.VISIBLE
                    toggleButton.text = context.getString(
                        if (expanded) R.string.forecast_hide_details else R.string.forecast_show_details,
                    )
                    toggleButton.setOnClickListener { onToggle() }
                }
                if (expanded && hasLines) {
                    val sb = StringBuilder()
                    if (row.incomeLines.isEmpty() && row.expenseLines.isEmpty()) {
                        sb.append(context.getString(R.string.forecast_vs_fact_no_details))
                    }
                    row.incomeLines.forEach { line ->
                        sb.append(context.getString(R.string.transactions_income_label))
                            .append(" · ").append(line.name)
                            .append(": ").append(MoneyFormat.formatRub(line.forecastAmount))
                            .append(" → ").append(MoneyFormat.formatRub(line.factAmount))
                            .append(" (").append(deltaText(context, line.delta)).append(")\n")
                    }
                    row.expenseLines.forEach { line ->
                        sb.append(line.name)
                            .append(": ").append(MoneyFormat.formatRub(line.forecastAmount))
                            .append(" → ").append(MoneyFormat.formatRub(line.factAmount))
                            .append(" (").append(deltaText(context, line.delta)).append(")\n")
                    }
                    detailsView.text = sb.toString().trimEnd()
                    detailsView.visibility = View.VISIBLE
                } else {
                    detailsView.visibility = View.GONE
                }
            }

            private fun deltaText(context: Context, delta: ForecastVsFactHelper.Delta): String {
                val sign = if (delta.absolute >= 0.0) "+" else "−"
                val money = MoneyFormat.formatRub(kotlin.math.abs(delta.absolute))
                val percent = delta.percent?.let { String.format("%.0f%%", kotlin.math.abs(it)) }
                    ?: context.getString(R.string.stats_no_data)
                return "$sign$money ($percent)"
            }
        }
    }
}
