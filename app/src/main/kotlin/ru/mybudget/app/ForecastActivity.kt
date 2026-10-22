package ru.mybudget.app

import android.os.Bundle
import androidx.room.withTransaction
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
    private var forecastJob: kotlinx.coroutines.Job? = null
    private var lastForecast: ForecastHelper.ForecastResult? = null
    private var lastBudgetId = 1
    private var lastScenario = scenario
    private var selectedBudgetId = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_forecast)
        manager = BudgetManager.getInstance(this)
        selectedBudgetId = savedInstanceState?.getInt("forecast_budget")
            ?: intent.getIntExtra(BudgetIntentExtras.BUDGET_ID, manager.getActiveBudgetId())
        horizon = runCatching { ForecastHelper.Horizon.valueOf(savedInstanceState?.getString("forecast_horizon").orEmpty()) }.getOrDefault(horizon)
        scenario = runCatching { ForecastHelper.Scenario.valueOf(savedInstanceState?.getString("forecast_scenario").orEmpty()) }.getOrDefault(scenario)
        ScreenHeaderHelper.setup(
            this,
            getString(R.string.forecast_title),
        )
        summaryView = findViewById(R.id.forecastSummary)
        findViewById<View>(R.id.forecastBudgetButton).setOnClickListener {
            BudgetPickerDialog.show(this, manager, selectedBudgetId) { id, _ ->
                selectedBudgetId = id
                refresh()
            }
        }
        findViewById<View>(R.id.forecastHint)?.let {
            ScreenHintHelper.bind(this, it, ScreenHintHelper.Keys.FORECAST, R.string.hint_forecast, showHelpLink = false)
        }
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
        findViewById<MaterialButton>(R.id.forecastVsFact).setOnClickListener {
            startActivity(android.content.Intent(this, ForecastVsFactActivity::class.java)
                .putExtra(BudgetIntentExtras.BUDGET_ID, selectedBudgetId))
        }
        findViewById<View>(R.id.forecastSaveSnapshot).setOnClickListener {
            lastForecast?.let { saveSnapshots(it, lastBudgetId, lastScenario.name) }
        }
        refresh()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("forecast_budget", selectedBudgetId)
        outState.putString("forecast_horizon", horizon.name)
        outState.putString("forecast_scenario", scenario.name)
        super.onSaveInstanceState(outState)
    }

    private fun refresh() {
        forecastJob?.cancel()
        val budgetId = selectedBudgetId
        val chosenHorizon = horizon
        val chosenScenario = scenario
        lastForecast = null
        findViewById<View>(R.id.forecastSaveSnapshot).isEnabled = false
        summaryView.setText(R.string.analysis_loading)
        findViewById<TextView>(R.id.forecastProjectedBalance).setText(R.string.analysis_value_pending)
        syncToggleStates()
        forecastJob = lifecycleScope.launch {
          try {
            val profiles = manager.getBudgetProfilesAsync()
            findViewById<TextView>(R.id.forecastBudgetButton).text = getString(R.string.forecast_budget_context,
                profiles.firstOrNull { it.id == budgetId }?.name ?: getString(R.string.budget_profiles_default_name))
            val result = withContext(Dispatchers.IO) { loadForecast(budgetId, chosenHorizon, chosenScenario) }
            if (budgetId != selectedBudgetId) return@launch
            lastForecast = result
            lastBudgetId = budgetId
            lastScenario = chosenScenario
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
                findViewById<TextView>(R.id.forecastProjectedBalance).apply {
                    text = MoneyFormat.formatRub(result.projectedEndBalance)
                    setTextColor(ContextCompat.getColor(context,
                        if (result.projectedEndBalance < 0.0) R.color.expense_red else R.color.text_primary))
                }
                findViewById<View>(R.id.forecastSaveSnapshot).isEnabled = true
            }
            syncToggleStates()
          } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled
          } catch (_: Exception) {
            summaryView.setText(R.string.analysis_error)
          }
        }
    }

    private fun saveSnapshots(result: ForecastHelper.ForecastResult, budgetId: Int, scenarioName: String) {
        lifecycleScope.launch(Dispatchers.IO) {
          try {
            manager.database.withTransaction {
            result.months.filterNot { it.isPartial }.forEach { month ->
                val external = month.copy(expenseLines = month.expenseLines.filterNot { it.source == ForecastHelper.ExpenseSource.GOAL },
                    totalExpense = MoneyFormat.roundMoney(month.totalExpense - month.goalsExpense),
                    net = MoneyFormat.roundMoney(month.net + month.goalsExpense))
                manager.repository.upsertForecastSnapshot(
                    ru.mybudget.app.data.ForecastSnapshotEntity(
                        budgetId = budgetId,
                        year = month.year,
                        month = month.month,
                        scenario = scenarioName,
                        totalIncome = month.totalIncome,
                        totalExpense = external.totalExpense,
                        net = external.net,
                        linesJson = ForecastSnapshotCodec.encode(external),
                    ),
                )
            }
            }
            withContext(Dispatchers.Main) { android.widget.Toast.makeText(this@ForecastActivity,
                "Прогноз сохранён", android.widget.Toast.LENGTH_SHORT).show() }
          } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled
          } catch (error: Exception) {
            withContext(Dispatchers.Main) { android.widget.Toast.makeText(this@ForecastActivity,
                error.message.orEmpty(), android.widget.Toast.LENGTH_LONG).show() }
          }
        }
    }

    private suspend fun loadForecast(budgetId: Int, chosenHorizon: ForecastHelper.Horizon,
        chosenScenario: ForecastHelper.Scenario): ForecastHelper.ForecastResult? {
        val inputs = ForecastDataLoader.load(manager, budgetId, chosenHorizon, chosenScenario)
        return ForecastHelper.build(inputs)
    }
    private fun syncToggleStates() {
        bindToggle(R.id.forecastHorizonQuarter, horizon == ForecastHelper.Horizon.QUARTER)
        bindToggle(R.id.forecastHorizonHalfYear, horizon == ForecastHelper.Horizon.HALF_YEAR)
        bindToggle(R.id.forecastHorizonYear, horizon == ForecastHelper.Horizon.YEAR)
        bindToggle(R.id.forecastScenarioOptimistic, scenario == ForecastHelper.Scenario.OPTIMISTIC)
        bindToggle(R.id.forecastScenarioRealistic, scenario == ForecastHelper.Scenario.REALISTIC)
    }

    private fun bindToggle(id: Int, selected: Boolean) {
        findViewById<MaterialButton>(id).apply {
            isSelected = selected
            backgroundTintList = android.content.res.ColorStateList.valueOf(ContextCompat.getColor(context,
                if (selected) R.color.md_theme_primary else R.color.card_background))
            setTextColor(ContextCompat.getColor(context, if (selected) R.color.md_theme_onPrimary else R.color.text_primary))
        }
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
                titleView.text = MonthBudgetComparisonHelper.monthLabel(month.year, month.month) + if (month.isPartial) " · остаток месяца" else ""
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
