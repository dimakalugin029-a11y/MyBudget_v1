package ru.mybudget.app

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.mybudget.app.setup.MigrationPreferences
import ru.mybudget.app.setup.MonthStartPreferences
import ru.mybudget.app.setup.PendingDistributionPreferences
import ru.mybudget.app.setup.RolloverPreferences

class MainActivity : AppCompatActivity() {
    private var monthStartLaunched = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<View>(R.id.mainSettingsButton).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<View>(R.id.mainProfilePicker).setOnClickListener {
            BudgetPicker.show(this, onSwitched = { refreshHeader() })
        }
        findViewById<TextView>(R.id.mainActiveBalanceText).apply {
            setOnClickListener {
                startActivity(Intent(this@MainActivity, BudgetActivity::class.java))
            }
            contentDescription = getString(R.string.main_balance_open_budget)
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
        ReminderScheduler.ensureScheduled(this)
        val prefs = getSharedPreferences(BudgetApplication.PREFS_NAME, MODE_PRIVATE)
        AppNotificationsHelper.maybeRequestNotificationPermissionOnLaunch(this)
        AppNotificationsHelper.maybeShowExportReminder(this, prefs)
        maybeShowUpgradeMigrationHint()
        maybeShowMonthStartWizard()
        runAutoBackupIfNeeded()
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    finishAffinity()
                }
            },
        )
    }

    override fun onResume() {
        super.onResume()
        refreshHeader()
        BudgetWidgetProvider.updateAll(this)
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
            val totalAllView = findViewById<TextView>(R.id.mainTotalAllText)
            if (profiles.size > 1) {
                val totalAll = manager.getTotalBalanceAll()
                totalAllView.visibility = View.VISIBLE
                totalAllView.text = getString(
                    R.string.main_total_all_short,
                    MoneyFormat.formatRub(totalAll),
                )
            } else {
                totalAllView.visibility = View.GONE
            }
            val summary = withContext(Dispatchers.IO) {
                MainDashboardHelper.loadSummary(this@MainActivity, manager)
            }
            bindAttentionSection(summary)
        }
    }

    private fun bindAttentionSection(summary: MainDashboardSummary) {
        bindAttentionRow(
            containerId = R.id.mainAttentionPending,
            rowId = R.id.mainAttentionPendingRow,
            icon = "⚖️",
            title = summary.pendingDistributionLine,
        ) {
            val pending = PendingDistributionPreferences.getPending(this) ?: return@bindAttentionRow
            startActivity(
                Intent(this, IncomeDistributionActivity::class.java)
                    .putExtra(IncomeDistributionActivity.EXTRA_TOTAL_INCOME, pending.amount)
                    .putExtra(IncomeDistributionActivity.EXTRA_INCOME_NOTE, pending.note)
                    .putExtra(BudgetIntentExtras.BUDGET_ID, pending.budgetId),
            )
        }
        bindAttentionRow(
            containerId = R.id.mainAttentionOverspend,
            rowId = R.id.mainAttentionOverspendRow,
            icon = "⚠️",
            title = summary.overspendLine,
        ) { startActivity(Intent(this, BudgetActivity::class.java)) }
        bindAttentionRow(
            containerId = R.id.mainAttentionUpcoming,
            rowId = R.id.mainAttentionUpcomingRow,
            icon = "📅",
            title = summary.upcomingPaymentsLine,
        ) { startActivity(Intent(this, PaymentCalendarActivity::class.java)) }
        bindAttentionRow(
            containerId = R.id.mainAttentionReminders,
            rowId = R.id.mainAttentionRemindersRow,
            icon = "🔔",
            title = summary.remindersLine,
        ) { startActivity(Intent(this, RemindersActivity::class.java)) }
        bindAttentionRow(
            containerId = R.id.mainAttentionGoals,
            rowId = R.id.mainAttentionGoalsRow,
            icon = "🎯",
            title = summary.goalsLine,
        ) { startActivity(Intent(this, GoalsActivity::class.java)) }
        bindAttentionRow(
            containerId = R.id.mainAttentionUtilities,
            rowId = R.id.mainAttentionUtilitiesRow,
            icon = "🏠",
            title = summary.utilitiesLine,
        ) { startActivity(Intent(this, UtilitiesActivity::class.java)) }
        bindAttentionRow(
            containerId = R.id.mainAttentionObligations,
            rowId = R.id.mainAttentionObligationsRow,
            icon = "📆",
            title = summary.obligationsLine,
        ) { startActivity(Intent(this, PlannedObligationsActivity::class.java)) }

        val anyVisible = listOf(
            R.id.mainAttentionPending,
            R.id.mainAttentionOverspend,
            R.id.mainAttentionUpcoming,
            R.id.mainAttentionReminders,
            R.id.mainAttentionGoals,
            R.id.mainAttentionUtilities,
            R.id.mainAttentionObligations,
        ).any { findViewById<View>(it).visibility == View.VISIBLE }
        findViewById<View>(R.id.mainAttentionSection).visibility =
            if (anyVisible) View.VISIBLE else View.GONE
    }

    private fun bindAttentionRow(
        containerId: Int,
        rowId: Int,
        icon: String,
        title: String?,
        onClick: () -> Unit,
    ) {
        val visible = !title.isNullOrBlank()
        findViewById<View>(containerId).visibility = if (visible) View.VISIBLE else View.GONE
        if (!visible) return
        MenuRowHelper.bind(findViewById(rowId), icon, title!!, onClick)
    }

    private fun bindRow(includeId: Int, iconRes: Int, titleRes: Int, target: Class<*>) {
        val row = findViewById<View>(includeId)
        row.findViewById<TextView>(R.id.rowIcon).setText(iconRes)
        row.findViewById<TextView>(R.id.rowTitle).setText(titleRes)
        row.setOnClickListener { startActivity(Intent(this, target)) }
    }

    private fun maybeShowMonthStartWizard() {
        if (monthStartLaunched) return
        if (!MonthStartPreferences.shouldShowWizard(this)) {
            maybeShowRolloverPrompt()
        } else {
            monthStartLaunched = true
            startActivity(Intent(this, MonthStartActivity::class.java))
        }
    }

    private fun maybeShowRolloverPrompt() {
        if (!RolloverPreferences.shouldPromptRollover(this)) return
        lifecycleScope.launch(Dispatchers.IO) {
            val manager = BudgetManager.getInstance(this@MainActivity)
            val all = manager.getCategoriesAsync()
            val budgetId = manager.getActiveBudgetId()
            val parents = all.associate { it.id to it.name }
            val candidates = BudgetRolloverHelper.candidates(all, parents, budgetId) {
                manager.hasSubcategories(it)
            }
            if (candidates.isEmpty()) {
                RolloverPreferences.dismissPromptForMonth(this@MainActivity)
                return@launch
            }
            withContext(Dispatchers.Main) {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle(R.string.rollover_prompt_title)
                    .setMessage(R.string.rollover_prompt_message)
                    .setPositiveButton(R.string.rollover_prompt_open) { _, _ ->
                        startActivity(Intent(this@MainActivity, RolloverActivity::class.java))
                    }
                    .setNegativeButton(R.string.rollover_prompt_later) { _, _ ->
                        RolloverPreferences.dismissPromptForMonth(this@MainActivity)
                    }
                    .show()
            }
        }
    }

    private fun maybeShowUpgradeMigrationHint() {
        if (MigrationPreferences.isUpgradeHintShown(this)) return
        AlertDialog.Builder(this)
            .setTitle(R.string.upgrade_hint_title)
            .setMessage(R.string.upgrade_hint_message)
            .setPositiveButton(R.string.upgrade_hint_import) { _, _ ->
                MigrationPreferences.markUpgradeHintShown(this)
                startActivity(Intent(this, SettingsActivity::class.java))
            }
            .setNegativeButton(R.string.upgrade_hint_dismiss) { _, _ ->
                MigrationPreferences.markUpgradeHintShown(this)
            }
            .setCancelable(false)
            .show()
    }

    private fun runAutoBackupIfNeeded() {
        lifecycleScope.launch {
            val result = AutoBackupHelper.maybeRunAutoExport(this@MainActivity) ?: return@launch
            if (result.success) {
                Toast.makeText(
                    this@MainActivity,
                    getString(R.string.auto_backup_success_toast, result.filename.orEmpty()),
                    Toast.LENGTH_LONG,
                ).show()
            } else if (!result.message.isNullOrBlank()) {
                Toast.makeText(
                    this@MainActivity,
                    getString(R.string.auto_backup_failed_toast, result.message),
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }
}
