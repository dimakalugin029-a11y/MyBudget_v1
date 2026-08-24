package ru.mybudget.app

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.mybudget.app.data.BudgetDatabase

class PlanFactActivity : AppCompatActivity() {
    data class PlanFactRow(
        val name: String,
        val planned: Double,
        val spent: Double,
        val fromObligation: Boolean = false,
        val runwayHint: String? = null,
    )

    private lateinit var manager: BudgetManager
    private lateinit var adapter: PlanFactAdapter
    private lateinit var emptyView: TextView
    private lateinit var recycler: RecyclerView
    private var selectedBudgetId = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_plan_fact)
        ScreenHeaderHelper.setup(this, getString(R.string.plan_fact_title), getString(R.string.main_icon_statistics))
        manager = BudgetManager.getInstance(this)
        selectedBudgetId = manager.getActiveBudgetId()
        emptyView = findViewById(R.id.planFactEmpty)
        recycler = findViewById(R.id.planFactRecycler)
        adapter = PlanFactAdapter()
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter
        BudgetPickerDialog.bind(
            this,
            manager,
            findViewById(R.id.transactionBudgetNameText),
            findViewById(R.id.transactionBudgetPicker),
            getSelectedBudgetId = { selectedBudgetId },
            onBudgetSelected = { id ->
                selectedBudgetId = id
                loadRows()
            },
        )
        loadRows()
    }

    private fun loadRows() {
        lifecycleScope.launch {
            val rows = withContext(Dispatchers.IO) {
                val dao = BudgetDatabase.getInstance(this@PlanFactActivity).budgetDao()
                val all = manager.getCategoriesAsync()
                val monthKey = MonthlyPlanHelper.currentMonth()
                val monthlyPlans = dao.getMonthlyPlansForBudgetMonth(
                    selectedBudgetId,
                    monthKey.year,
                    monthKey.month,
                ).associateBy { it.categoryId }
                val (startMs, endMs) = MonthlyPlanHelper.monthRangeMs(monthKey.year, monthKey.month)
                val obligations = dao.getPlannedObligationsByBudgetOnce(selectedBudgetId)
                val obligationPlan = PlannedObligationHelper.monthlyPlanByCategory(obligations)
                val parents = all.associate { it.id to it.name }
                val leaves = all.filter { cat ->
                    cat.budgetId == selectedBudgetId && cat.isActive && !manager.hasSubcategories(cat.id)
                }
                leaves.mapNotNull { cat ->
                    val spent = dao.getExpenseSumForCategoryInRange(cat.id, startMs, endMs)
                    val obligationMonthly = obligationPlan[cat.id] ?: 0.0
                    val monthly = MonthlyPlanHelper.effectivePlannedAmount(cat, monthlyPlans[cat.id])
                    val planned = if (monthly > 0.0) {
                        monthly
                    } else {
                        PlannedObligationHelper.effectivePlan(cat.plannedAmount, obligationMonthly)
                    }
                    if (planned <= 0.0 && spent <= 0.0) return@mapNotNull null
                    val name = if (cat.parentId != 0) {
                        "${parents[cat.parentId] ?: "?"} → ${cat.name}"
                    } else {
                        cat.name
                    }
                    val fromObligation = monthly <= 0.0 && cat.plannedAmount <= 0.0 && obligationMonthly > 0.0
                    PlanFactRow(
                        name = name,
                        planned = planned,
                        spent = spent,
                        fromObligation = fromObligation,
                        runwayHint = CategoryRunwayHelper.formatRunwaySuffix(
                            this@PlanFactActivity,
                            cat.currentBalance,
                            spent,
                        ),
                    )
                }.sortedByDescending { it.spent }
            }
            adapter.submit(rows)
            val empty = rows.isEmpty()
            emptyView.visibility = if (empty) View.VISIBLE else View.GONE
            recycler.visibility = if (empty) View.GONE else View.VISIBLE
        }
    }

    private class PlanFactAdapter : RecyclerView.Adapter<PlanFactAdapter.Holder>() {
        private var rows: List<PlanFactRow> = emptyList()

        fun submit(data: List<PlanFactRow>) {
            rows = data
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_plan_fact_row, parent, false)
            return Holder(view)
        }

        override fun getItemCount(): Int = rows.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val row = rows[position]
            val ctx = holder.itemView.context
            holder.name.text = row.name
            val plannedRes = if (row.fromObligation) R.string.plan_fact_planned_obligations else R.string.plan_fact_planned
            val plannedValue = if (row.planned > 0.0) {
                MoneyFormat.formatRub(row.planned)
            } else {
                ctx.getString(R.string.plan_fact_no_plan)
            }
            holder.planned.text = "${ctx.getString(plannedRes)}: $plannedValue"
            holder.spent.text = "${ctx.getString(R.string.plan_fact_spent)}: ${MoneyFormat.formatRub(row.spent)}"
            val diff = row.planned - row.spent
            holder.diff.text = "${ctx.getString(R.string.plan_fact_diff)}: ${MoneyFormat.formatRub(diff)}"
            val diffColor = when {
                row.planned <= 0.0 -> R.color.text_secondary
                diff >= 0.0 -> R.color.primary_green
                else -> R.color.expense_red
            }
            holder.diff.setTextColor(ContextCompat.getColor(ctx, diffColor))
            val pct = if (row.planned > 0.0) {
                ((row.spent / row.planned) * 100.0).toInt().coerceIn(0, 100)
            } else {
                0
            }
            holder.progress.progress = pct
            if (row.runwayHint.isNullOrBlank()) {
                holder.runway.visibility = View.GONE
            } else {
                holder.runway.visibility = View.VISIBLE
                holder.runway.text = row.runwayHint
            }
        }

        class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val name: TextView = view.findViewById(R.id.planFactCategoryName)
            val planned: TextView = view.findViewById(R.id.planFactPlanned)
            val spent: TextView = view.findViewById(R.id.planFactSpent)
            val diff: TextView = view.findViewById(R.id.planFactDiff)
            val progress: ProgressBar = view.findViewById(R.id.planFactProgress)
            val runway: TextView = view.findViewById(R.id.planFactRunway)
        }
    }
}
