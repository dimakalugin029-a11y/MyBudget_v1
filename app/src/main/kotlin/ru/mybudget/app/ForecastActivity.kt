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
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ForecastActivity : AppCompatActivity() {
    private lateinit var manager: BudgetManager
    private lateinit var adapter: ForecastAdapter
    private lateinit var summaryView: TextView
    private lateinit var emptyView: TextView

    private var horizon = ForecastHelper.Horizon.QUARTER
    private var scenario = ForecastHelper.Scenario.OPTIMISTIC

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_forecast)
        manager = BudgetManager.getInstance(this)
        ScreenHeaderHelper.setup(
            this,
            getString(R.string.forecast_title),
            getString(R.string.main_icon_statistics),
        )
        summaryView = findViewById(R.id.forecastSummary)
        emptyView = findViewById(R.id.forecastEmpty)
        adapter = ForecastAdapter()
        findViewById<RecyclerView>(R.id.forecastList).apply {
            layoutManager = LinearLayoutManager(this@ForecastActivity)
            adapter = this@ForecastActivity.adapter
        }
        findViewById<MaterialButton>(R.id.forecastHorizonQuarter).setOnClickListener {
            horizon = ForecastHelper.Horizon.QUARTER
            refresh()
        }
        findViewById<MaterialButton>(R.id.forecastHorizonHalfYear).setOnClickListener {
            horizon = ForecastHelper.Horizon.HALF_YEAR
            refresh()
        }
        findViewById<MaterialButton>(R.id.forecastHorizonYear).setOnClickListener {
            horizon = ForecastHelper.Horizon.YEAR
            refresh()
        }
        findViewById<MaterialButton>(R.id.forecastScenarioOptimistic).setOnClickListener {
            scenario = ForecastHelper.Scenario.OPTIMISTIC
            refresh()
        }
        findViewById<MaterialButton>(R.id.forecastScenarioRealistic).setOnClickListener {
            scenario = ForecastHelper.Scenario.REALISTIC
            refresh()
        }
        refresh()
    }

    private fun refresh() {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { loadForecast() }
            adapter.submit(result?.months ?: emptyList())
            if (result == null) {
                summaryView.text = ""
                emptyView.visibility = View.VISIBLE
            } else {
                emptyView.visibility = View.GONE
                summaryView.text = getString(
                    R.string.forecast_summary_totals,
                    MoneyFormat.formatRub(result.totalIncome),
                    MoneyFormat.formatRub(result.totalExpense),
                    MoneyFormat.formatRub(result.totalNet),
                )
            }
            syncToggleStates()
        }
    }

    private suspend fun loadForecast(): ForecastHelper.ForecastResult? {
        manager.getCategoriesAsync()
        val budgetId = manager.getActiveBudgetId()
        val categories = manager.getCategories()
        val categoryNames = categories.associate { it.id to it.name }
        val parents = categories.associate { it.id to it.name }
        val incomeSources = manager.repository.getPlannedIncomeSourcesByBudgetOnce(budgetId)
        val obligations = manager.repository.getPlannedObligationsByBudgetOnce(budgetId)
        val monthlyPlans = manager.repository.getMonthlyPlansForBudget(budgetId)
        val goals = manager.repository.getAllSavingsGoalsList()
        val pastMonths = MonthBudgetComparisonHelper.recentMonths().take(3)
        val pastRanges = pastMonths.map { MonthBudgetComparisonHelper.monthRangeMs(it.first, it.second) }
        val pastTransactions = manager.repository.getTransactionsInRange(
            pastRanges.minOf { it.first },
            System.currentTimeMillis(),
        )
        val startBalance = manager.getTotalBalance(budgetId)
        val goalBalances = goals.associate { it.categoryId to manager.getCategoryBalanceWithSubcategories(it.categoryId) }
        val result = ForecastHelper.build(
            ForecastHelper.Inputs(
                startBalance = startBalance,
                today = java.time.LocalDate.now(),
                horizon = horizon,
                scenario = scenario,
                incomeSources = incomeSources,
                obligations = obligations,
                monthlyPlans = monthlyPlans,
                pastTransactions = pastTransactions,
                goals = goals,
                goalBalances = goalBalances,
                categoryNames = categoryNames,
            ),
        )
        if (result.months.isEmpty()) return null
        return result.copy(
            months = result.months.map { month ->
                month.copy(
                    incomeLines = month.incomeLines,
                    expenseLines = month.expenseLines.map { line ->
                        val name = if (line.source == ForecastHelper.ExpenseSource.GOAL) {
                            line.name
                        } else {
                            val cat = categories.firstOrNull { it.id == line.categoryId }
                            if (cat != null) CategoryMultiPicker.leafLabel(cat, parents) else line.name
                        }
                        line.copy(name = name)
                    },
                )
            },
        )
    }

    private fun syncToggleStates() {
        bindToggle(R.id.forecastHorizonQuarter, horizon == ForecastHelper.Horizon.QUARTER)
        bindToggle(R.id.forecastHorizonHalfYear, horizon == ForecastHelper.Horizon.HALF_YEAR)
        bindToggle(R.id.forecastHorizonYear, horizon == ForecastHelper.Horizon.YEAR)
        bindToggle(R.id.forecastScenarioOptimistic, scenario == ForecastHelper.Scenario.OPTIMISTIC)
        bindToggle(R.id.forecastScenarioRealistic, scenario == ForecastHelper.Scenario.REALISTIC)
    }

    private fun bindToggle(id: Int, selected: Boolean) {
        findViewById<MaterialButton>(id).isEnabled = !selected
    }

    private class ForecastAdapter : RecyclerView.Adapter<ForecastAdapter.Holder>() {
        private var items: List<ForecastHelper.ForecastMonth> = emptyList()
        private val expanded = mutableSetOf<Int>()

        fun submit(data: List<ForecastHelper.ForecastMonth>) {
            items = data
            expanded.clear()
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_forecast_month, parent, false)
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
            private val titleView = itemView.findViewById<TextView>(R.id.forecastMonthTitle)
            private val incomeView = itemView.findViewById<TextView>(R.id.forecastMonthIncome)
            private val expenseView = itemView.findViewById<TextView>(R.id.forecastMonthExpense)
            private val netView = itemView.findViewById<TextView>(R.id.forecastMonthNet)
            private val balanceView = itemView.findViewById<TextView>(R.id.forecastMonthBalance)
            private val toggleButton = itemView.findViewById<MaterialButton>(R.id.forecastMonthToggle)
            private val detailsView = itemView.findViewById<TextView>(R.id.forecastMonthDetails)

            fun bind(month: ForecastHelper.ForecastMonth, expanded: Boolean, onToggle: () -> Unit) {
                val context = itemView.context
                titleView.text = MonthBudgetComparisonHelper.monthLabel(month.year, month.month)
                incomeView.text = context.getString(
                    R.string.forecast_month_income,
                    MoneyFormat.formatRub(month.totalIncome),
                )
                expenseView.text = context.getString(
                    R.string.forecast_month_expense,
                    MoneyFormat.formatRub(month.totalExpense),
                )
                netView.text = context.getString(
                    R.string.forecast_month_net,
                    MoneyFormat.formatRub(month.net),
                )
                netView.setTextColor(
                    ContextCompat.getColor(
                        context,
                        if (month.net >= 0.0) R.color.income_green else R.color.expense_red,
                    ),
                )
                balanceView.text = context.getString(
                    R.string.forecast_month_balance,
                    MoneyFormat.formatRub(month.projectedEndBalance),
                )
                if (month.expenseLines.isEmpty() && month.incomeLines.isEmpty()) {
                    toggleButton.visibility = View.GONE
                } else {
                    toggleButton.visibility = View.VISIBLE
                    toggleButton.text = context.getString(
                        if (expanded) R.string.forecast_hide_details else R.string.forecast_show_details,
                    )
                    toggleButton.setOnClickListener { onToggle() }
                }
                if (expanded) {
                    val sb = StringBuilder()
                    month.incomeLines.forEach { line ->
                        sb.append(context.getString(R.string.transactions_income_label))
                            .append(" · ").append(line.name)
                            .append(": ").append(MoneyFormat.formatRub(line.amount))
                            .append('\n')
                    }
                    month.expenseLines.forEach { line ->
                        sb.append(line.name)
                            .append(" (").append(sourceLabel(context, line.source)).append(")")
                            .append(": ").append(MoneyFormat.formatRub(line.amount))
                            .append('\n')
                    }
                    detailsView.text = sb.toString().trimEnd()
                    detailsView.visibility = View.VISIBLE
                } else {
                    detailsView.visibility = View.GONE
                }
            }

            private fun sourceLabel(context: android.content.Context, source: ForecastHelper.ExpenseSource): String {
                return context.getString(
                    when (source) {
                        ForecastHelper.ExpenseSource.PLAN -> R.string.forecast_source_plan
                        ForecastHelper.ExpenseSource.OBLIGATION -> R.string.forecast_source_obligation
                        ForecastHelper.ExpenseSource.AVG_FACT -> R.string.forecast_source_avg_fact
                        ForecastHelper.ExpenseSource.GOAL -> R.string.forecast_source_goal
                    },
                )
            }
        }
    }
}
