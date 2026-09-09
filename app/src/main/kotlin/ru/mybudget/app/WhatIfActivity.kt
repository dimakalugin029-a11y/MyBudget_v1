package ru.mybudget.app

import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import ru.mybudget.app.data.PlannedObligationEntity
import ru.mybudget.app.data.SavingsGoalEntity
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class WhatIfActivity : AppCompatActivity() {
    private lateinit var manager: BudgetManager
    private var goals: List<Pair<SavingsGoalEntity, Double>> = emptyList()
    private var obligations: List<PlannedObligationEntity> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_what_if)
        manager = BudgetManager.getInstance(this)
        ScreenHeaderHelper.setup(this, getString(R.string.what_if_title), "🎯")
        findViewById<com.google.android.material.button.MaterialButton>(R.id.whatIfGoalCalculate)
            .setOnClickListener { calculateGoal() }
        findViewById<com.google.android.material.button.MaterialButton>(R.id.whatIfObligationCalculate)
            .setOnClickListener { calculateObligation() }
        loadData()
    }

    private fun loadData() {
        lifecycleScope.launch {
            manager.getCategoriesAsync()
            goals = manager.repository.getAllSavingsGoals().first()
                .filter { it.isActive }
                .map { goal ->
                    goal to manager.getCategoryBalanceWithSubcategories(goal.categoryId)
                }
            obligations = manager.repository.getPlannedObligationsByBudgetOnce(manager.getActiveBudgetId())
                .filter { it.isActive }
            val goalLabels = goals.map { (goal, balance) ->
                "${goal.name} (${MoneyFormat.formatRub(balance)} / ${MoneyFormat.formatRub(goal.targetAmount)})"
            }
            findViewById<Spinner>(R.id.whatIfGoalSpinner).adapter = ArrayAdapter(
                this@WhatIfActivity,
                android.R.layout.simple_spinner_dropdown_item,
                goalLabels.ifEmpty { listOf(getString(R.string.what_if_no_goals)) },
            )
            findViewById<Spinner>(R.id.whatIfObligationSpinner).adapter = ArrayAdapter(
                this@WhatIfActivity,
                android.R.layout.simple_spinner_dropdown_item,
                obligations.map { "${it.name} — ${MoneyFormat.formatRub(it.amount)}" }
                    .ifEmpty { listOf(getString(R.string.what_if_no_obligations)) },
            )
        }
    }

    private fun calculateGoal() {
        if (goals.isEmpty()) {
            Toast.makeText(this, R.string.what_if_no_goals, Toast.LENGTH_SHORT).show()
            return
        }
        val index = findViewById<Spinner>(R.id.whatIfGoalSpinner).selectedItemPosition
        val (goal, balance) = goals.getOrNull(index) ?: return
        val extra = findViewById<EditText>(R.id.whatIfExtraInput).text.toString()
            .replace(" ", "").replace(",", ".").toDoubleOrNull() ?: 0.0
        val days = GoalProgressHelper.daysUntilDeadline(goal.deadline)
        val result = WhatIfScenarioHelper.simulateGoalWithExtra(
            goalName = goal.name,
            currentBalance = balance,
            targetAmount = goal.targetAmount,
            extraPerMonth = extra,
            daysUntilDeadline = days,
        )
        val resultView = findViewById<TextView>(R.id.whatIfGoalResult)
        resultView.text = when {
            result.remaining <= 0.0 -> getString(R.string.what_if_goal_done, result.goalName)
            result.monthsToComplete == null -> getString(R.string.what_if_goal_unreachable, result.goalName)
            else -> getString(
                R.string.what_if_goal_result,
                result.goalName,
                result.monthsToComplete,
                result.projectedDateLabel.orEmpty(),
                MoneyFormat.formatRub(result.extraPerMonth),
            )
        }
    }

    private fun calculateObligation() {
        if (obligations.isEmpty()) {
            Toast.makeText(this, R.string.what_if_no_obligations, Toast.LENGTH_SHORT).show()
            return
        }
        val index = findViewById<Spinner>(R.id.whatIfObligationSpinner).selectedItemPosition
        val obligation = obligations.getOrNull(index) ?: return
        val nextMonth = Calendar.getInstance().apply { add(Calendar.MONTH, 1) }
        val nextLabel = SimpleDateFormat("LLLL yyyy", Locale("ru")).format(nextMonth.time)
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale("ru")) else it.toString() }
        val result = WhatIfScenarioHelper.simulateObligationShiftByMonth(
            obligationName = obligation.name,
            amount = obligation.amount,
            nextMonthLabel = nextLabel,
        )
        findViewById<TextView>(R.id.whatIfObligationResult).text = getString(
            R.string.what_if_obligation_result,
            result.obligationName,
            MoneyFormat.formatRub(result.freedThisMonth),
            result.dueNextMonthLabel,
            MoneyFormat.formatRub(result.amount),
        )
    }
}
