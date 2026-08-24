package ru.mybudget.app

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.mybudget.app.data.BudgetDatabase
import ru.mybudget.app.setup.MonthStartPreferences
import kotlin.math.max

class MonthStartActivity : AppCompatActivity() {
    private lateinit var stepLabel: TextView
    private lateinit var emoji: TextView
    private lateinit var titleView: TextView
    private lateinit var body: TextView
    private lateinit var actionButton: MaterialButton
    private lateinit var skipButton: MaterialButton
    private lateinit var nextButton: MaterialButton

    private var step = 0
    private var rolloverCount = 0
    private var expensePlanCount = 0
    private var expenseDefaultCount = 0
    private var obligationMonthly = 0.0
    private var obligationUnlinked = 0
    private var tariffLinesMissing = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_month_start)
        ScreenHeaderHelper.setup(this, getString(R.string.month_start_title), getString(R.string.main_icon_budget))
        stepLabel = findViewById(R.id.monthStartStepLabel)
        emoji = findViewById(R.id.monthStartEmoji)
        titleView = findViewById(R.id.monthStartTitle)
        body = findViewById(R.id.monthStartBody)
        actionButton = findViewById(R.id.monthStartActionButton)
        skipButton = findViewById(R.id.monthStartSkipButton)
        nextButton = findViewById(R.id.monthStartNextButton)
        skipButton.setOnClickListener { finishWizard() }
        nextButton.setOnClickListener { advanceStep() }
        loadStepData()
        showStep()
    }

    override fun onResume() {
        super.onResume()
        if (step > 0) loadStepData()
    }

    private fun loadStepData() {
        lifecycleScope.launch(Dispatchers.IO) {
            val manager = BudgetManager.getInstance(this@MonthStartActivity)
            val budgetId = manager.getActiveBudgetId()
            val all = manager.getCategoriesAsync()
            val parents = all.associate { it.id to it.name }
            rolloverCount = BudgetRolloverHelper.candidates(all, parents, budgetId) { manager.hasSubcategories(it) }.size
            val dao = BudgetDatabase.getInstance(this@MonthStartActivity).budgetDao()
            val currentMonth = MonthlyPlanHelper.currentMonth()
            val leaves = all.filter { cat ->
                cat.budgetId == budgetId && cat.isActive && !manager.hasSubcategories(cat.id)
            }
            expenseDefaultCount = leaves.count { it.defaultPlannedAmount > 0.0 }
            val monthlyPlans = dao.getMonthlyPlansForBudgetMonth(budgetId, currentMonth.year, currentMonth.month)
            expensePlanCount = monthlyPlans.count { it.isEnabled && it.plannedAmount > 0.0 }
            val utilityDao = BudgetDatabase.getInstance(this@MonthStartActivity).utilityDao()
            val obligations = dao.getPlannedObligationsByBudgetOnce(budgetId)
            obligationMonthly = PlannedObligationHelper.totalMonthly(obligations)
            obligationUnlinked = PlannedObligationHelper.unlinkedCount(obligations)
            val tariffLines = utilityDao.getTemplateTariffLineCount()
            val filledTariffs = utilityDao.getFilledTariffCount()
            tariffLinesMissing = max(tariffLines - filledTariffs, 0)
            withContext(Dispatchers.Main) {
                if (step in 1..4) showStep()
            }
        }
    }

    private fun showStep() {
        actionButton.visibility = View.GONE
        actionButton.setOnClickListener(null)
        when (step) {
            0 -> {
                stepLabel.text = getString(R.string.month_start_step, 1, 5)
                emoji.text = "📅"
                titleView.text = getString(R.string.month_start_intro_title)
                body.text = getString(R.string.month_start_intro_body)
                nextButton.text = getString(R.string.month_start_next)
            }
            1 -> {
                stepLabel.text = getString(R.string.month_start_step, 2, 5)
                emoji.text = "🔄"
                titleView.text = getString(R.string.month_start_rollover_title)
                body.text = if (rolloverCount > 0) {
                    getString(R.string.month_start_rollover_body, rolloverCount)
                } else {
                    getString(R.string.month_start_rollover_none)
                }
                if (rolloverCount > 0) {
                    showAction(getString(R.string.month_start_open_rollover)) {
                        startActivity(Intent(this, RolloverActivity::class.java))
                    }
                }
                nextButton.text = getString(R.string.month_start_next)
            }
            2 -> {
                stepLabel.text = getString(R.string.month_start_step, 3, 5)
                emoji.text = "📊"
                titleView.text = getString(R.string.month_start_expense_plan_title)
                body.text = when {
                    expensePlanCount > 0 -> getString(R.string.month_start_expense_plan_body_ready, expensePlanCount)
                    expenseDefaultCount > 0 -> getString(R.string.month_start_expense_plan_body_defaults, expenseDefaultCount)
                    else -> getString(R.string.month_start_expense_plan_body_none)
                }
                showAction(getString(R.string.month_start_open_expense_plan)) {
                    startActivity(Intent(this, ExpensePlanActivity::class.java))
                }
                nextButton.text = getString(R.string.month_start_next)
            }
            3 -> {
                stepLabel.text = getString(R.string.month_start_step, 4, 5)
                emoji.text = "📆"
                titleView.text = getString(R.string.month_start_obligations_title)
                body.text = when {
                    obligationMonthly <= 0.0 -> getString(R.string.month_start_obligations_none)
                    obligationUnlinked > 0 -> getString(
                        R.string.month_start_obligations_body_unlinked,
                        MoneyFormat.formatRub(obligationMonthly),
                        obligationUnlinked,
                    )
                    else -> getString(R.string.month_start_obligations_body, MoneyFormat.formatRub(obligationMonthly))
                }
                showAction(getString(R.string.month_start_open_obligations)) {
                    startActivity(Intent(this, PlannedObligationsActivity::class.java))
                }
                nextButton.text = getString(R.string.month_start_next)
            }
            4 -> {
                stepLabel.text = getString(R.string.month_start_step, 5, 5)
                emoji.text = "🏠"
                titleView.text = getString(R.string.month_start_utilities_title)
                body.text = if (tariffLinesMissing > 0) {
                    getString(R.string.month_start_utilities_tariffs_missing, tariffLinesMissing)
                } else {
                    getString(R.string.month_start_utilities_ok)
                }
                if (tariffLinesMissing > 0) {
                    showAction(getString(R.string.month_start_open_tariffs)) {
                        startActivity(Intent(this, UtilityTariffsActivity::class.java))
                    }
                }
                nextButton.text = getString(R.string.month_start_finish)
            }
        }
    }

    private fun showAction(label: String, onClick: () -> Unit) {
        actionButton.visibility = View.VISIBLE
        actionButton.text = label
        actionButton.setOnClickListener { onClick() }
    }

    private fun advanceStep() {
        if (step >= 4) {
            finishWizard()
        } else {
            step++
            showStep()
        }
    }

    private fun finishWizard() {
        MonthStartPreferences.markDone(this)
        setResult(RESULT_OK)
        finish()
    }
}
