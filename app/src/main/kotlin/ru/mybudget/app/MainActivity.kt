package ru.mybudget.app

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<View>(R.id.mainSettingsButton).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<View>(R.id.mainProfilePicker).setOnClickListener {
            BudgetPicker.show(this, onSwitched = { refreshHeader() })
        }
        findViewById<View>(R.id.mainActiveBalanceText).setOnClickListener {
            startActivity(Intent(this, BudgetActivity::class.java))
        }
        findViewById<View>(R.id.incomeButton).setOnClickListener {
            startActivity(Intent(this, IncomeActivity::class.java))
        }
        findViewById<View>(R.id.expenseButton).setOnClickListener {
            startActivity(Intent(this, ExpenseActivity::class.java))
        }
        findViewById<View>(R.id.budgetButton).setOnClickListener {
            startActivity(Intent(this, BudgetActivity::class.java))
        }

        bindRow(R.id.transactionsButton, R.string.main_icon_transactions, R.string.main_menu_transactions, TransactionsActivity::class.java)
        bindRow(R.id.statisticsButton, R.string.main_icon_statistics, R.string.main_menu_statistics, StatisticsActivity::class.java)
        bindRow(R.id.expensePlanButton, R.string.main_icon_expense_plan, R.string.main_menu_expense_plan, ExpensePlanActivity::class.java)
        bindRow(R.id.goalsButton, R.string.main_icon_goals, R.string.main_menu_goals, GoalsActivity::class.java)
        bindRow(R.id.remindersButton, R.string.main_icon_reminders, R.string.main_menu_reminders, RemindersActivity::class.java)
        bindRow(R.id.obligationsButton, R.string.main_icon_obligations, R.string.main_menu_obligations, PlannedObligationsActivity::class.java)
        bindRow(R.id.utilitiesButton, R.string.main_icon_utilities, R.string.main_menu_utilities, UtilitiesActivity::class.java)
    }

    override fun onResume() {
        super.onResume()
        refreshHeader()
    }

    private fun refreshHeader() {
        lifecycleScope.launch {
            val manager = BudgetManager.getInstance(this@MainActivity)
            manager.getCategoriesAsync(forceReload = true)
            val profiles = manager.getBudgetProfilesAsync()
            val activeId = manager.getActiveBudgetId()
            val profile = profiles.firstOrNull { it.id == activeId }
            findViewById<TextView>(R.id.mainProfileNameText).text =
                profile?.name ?: getString(R.string.budget_profiles_default_name)
            findViewById<TextView>(R.id.mainActiveBalanceText).text =
                MoneyFormat.formatRub(manager.getTotalBalance(activeId))
        }
    }

    private fun bindRow(includeId: Int, iconRes: Int, titleRes: Int, target: Class<*>) {
        val row = findViewById<View>(includeId)
        row.findViewById<TextView>(R.id.rowIcon).setText(iconRes)
        row.findViewById<TextView>(R.id.rowTitle).setText(titleRes)
        row.setOnClickListener { startActivity(Intent(this, target)) }
    }
}
