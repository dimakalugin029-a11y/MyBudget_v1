package ru.mybudget.app

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.mybudget.app.setup.CompactUiPreferences
import ru.mybudget.app.setup.AutoBackupPreferences
import ru.mybudget.app.setup.ExpenseShortcut
import ru.mybudget.app.setup.ExpenseShortcutPreferences
import ru.mybudget.app.setup.MigrationPreferences
import ru.mybudget.app.setup.MonthStartPreferences
import ru.mybudget.app.setup.PendingDistributionPreferences
import ru.mybudget.app.setup.ParticipantPreferences
import ru.mybudget.app.setup.QuickExpensePreferences
import ru.mybudget.app.setup.RolloverPreferences
import ru.mybudget.app.setup.SetupChecklistHelper
import ru.mybudget.app.setup.SetupChecklistPreferences

class MainActivity : AppCompatActivity() {
    private var currentSection = TopLevelNavigation.Destination.OVERVIEW
    private val sectionScroll = mutableMapOf<TopLevelNavigation.Destination, Int>()
    private var recentRefreshJob: kotlinx.coroutines.Job? = null
    private var planRefreshJob: kotlinx.coroutines.Job? = null
    private var monthStartLaunched = false
    private var headerRefreshJob: kotlinx.coroutines.Job? = null
    private var pendingRefreshJob: kotlinx.coroutines.Job? = null
    private var dashboardDay = java.time.LocalDate.now()
    private val dashboardObserver = object : androidx.room.InvalidationTracker.Observer(
        "categories", "budget_profiles", "transactions", "payment_reminders", "recurring_transactions",
        "planned_obligations", "obligation_payments", "planned_income_sources", "savings_goals",
        "monthly_category_plans", "monthly_income_plans", "utility_properties", "utility_bills",
        "utility_sections", "utility_line_items",
    ) {
        override fun onInvalidated(tables: Set<String>) {
            runOnUiThread {
                if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) {
                    pendingRefreshJob?.cancel()
                    pendingRefreshJob = lifecycleScope.launch {
                        kotlinx.coroutines.delay(250)
                        refreshHeader()
                    }
                }
            }
        }
    }
    private val dateReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: Intent?) {
            val day = java.time.LocalDate.now()
            if (day != dashboardDay || intent?.action != Intent.ACTION_TIME_TICK) {
                dashboardDay = day
                refreshHeader()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        BudgetManager.getInstance(this).database.invalidationTracker.addObserver(dashboardObserver)
        val filter = android.content.IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_DATE_CHANGED)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        ContextCompat.registerReceiver(this, dateReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onStop() {
        BudgetManager.getInstance(this).database.invalidationTracker.removeObserver(dashboardObserver)
        unregisterReceiver(dateReceiver)
        pendingRefreshJob?.cancel()
        headerRefreshJob?.cancel()
        recentRefreshJob?.cancel()
        planRefreshJob?.cancel()
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<View>(R.id.mainSettingsButton).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<View>(R.id.mainSearchButton).setOnClickListener {
            startActivity(Intent(this, SearchActivity::class.java))
        }
        findViewById<View>(R.id.mainProfilePicker).setOnClickListener {
            BudgetPicker.show(this, onSwitched = { refreshHeader() })
        }
        findViewById<View>(R.id.mainPlanBudgetButton).setOnClickListener {
            BudgetPicker.show(this, onSwitched = { refreshHeader() })
        }
        findViewById<TextView>(R.id.mainActiveBalanceText).apply {
            setOnClickListener {
                startActivity(Intent(this@MainActivity, BudgetActivity::class.java))
            }
            contentDescription = getString(R.string.main_balance_open_budget)
        }
        findViewById<View>(R.id.mainAddOperationButton).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(R.string.main_add_operation)
                .setItems(arrayOf(getString(R.string.main_primary_expense), getString(R.string.main_primary_income))) { _, which ->
                    val target = if (which == 0) QuickExpenseActivity::class.java else IncomeActivity::class.java
                    startActivity(Intent(this, target))
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        bindRow(R.id.transactionsButton, R.string.main_menu_transactions, TransactionsActivity::class.java)
        bindRow(R.id.expensePlanButton, R.string.main_menu_expense_plan, ExpensePlanActivity::class.java)
        bindRow(R.id.incomePlanButton, R.string.main_menu_income_plan, PlannedIncomeActivity::class.java)
        bindRow(R.id.goalsButton, R.string.main_menu_goals, GoalsActivity::class.java)
        bindRow(R.id.yearBudgetingButton, R.string.main_menu_year_budgeting, YearBudgetingActivity::class.java)
        bindRow(R.id.paymentCalendarButton, R.string.main_menu_payment_calendar, PaymentCalendarActivity::class.java)
        bindRow(R.id.obligationsButton, R.string.main_menu_obligations, PlannedObligationsActivity::class.java)
        bindRow(R.id.recurringButton, R.string.main_menu_recurring, RecurringActivity::class.java)
        bindRow(R.id.utilitiesButton, R.string.main_menu_utilities, UtilitiesActivity::class.java)
        bindRow(R.id.autoButton, R.string.main_menu_auto, AutoActivity::class.java)
        bindRow(R.id.mainFamilyRow, R.string.main_family_menu, FamilySyncWizardActivity::class.java)
        bindRow(R.id.mainSettingsRow, R.string.main_menu_settings, SettingsActivity::class.java)
        bindRow(R.id.mainHelpRow, R.string.help_title, HelpActivity::class.java)
        TopLevelNavigation.bind(this, currentSection, { showDestination(it) }) {
            findViewById<androidx.core.widget.NestedScrollView>(R.id.mainScroll).smoothScrollTo(0, 0)
        }
        for (destination in TopLevelNavigation.Destination.entries) {
            sectionScroll[destination] = savedInstanceState?.getInt("scroll_${destination.name}") ?: 0
        }
        showDestination(TopLevelNavigation.Destination.fromName(
            savedInstanceState?.getString("main_section") ?: intent.getStringExtra(TopLevelNavigation.EXTRA_DESTINATION),
        ), rememberScroll = false)
        intent.removeExtra(TopLevelNavigation.EXTRA_DESTINATION)
        ReminderScheduler.ensureScheduled(this)
        WeeklySummaryScheduler.ensureScheduled(this)
        val prefs = getSharedPreferences(BudgetApplication.PREFS_NAME, MODE_PRIVATE)
        AppNotificationsHelper.maybeRequestNotificationPermissionOnLaunch(this)
        AppNotificationsHelper.maybeShowExportReminder(this, prefs)
        maybeShowUpgradeMigrationHint()
        maybeShowMonthStartWizard()
        runAutoBackupIfNeeded()
        maybeProcessMeterReadingReminder()
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (currentSection != TopLevelNavigation.Destination.OVERVIEW) {
                        showDestination(TopLevelNavigation.Destination.OVERVIEW)
                    } else {
                        finishAffinity()
                    }
                }
            },
        )
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        showDestination(TopLevelNavigation.Destination.fromName(intent.getStringExtra(TopLevelNavigation.EXTRA_DESTINATION)))
        intent.removeExtra(TopLevelNavigation.EXTRA_DESTINATION)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        sectionScroll[currentSection] = findViewById<androidx.core.widget.NestedScrollView>(R.id.mainScroll).scrollY
        outState.putString("main_section", currentSection.name)
        sectionScroll.forEach { (section, y) -> outState.putInt("scroll_${section.name}", y) }
        super.onSaveInstanceState(outState)
    }

    private fun showDestination(destination: TopLevelNavigation.Destination, rememberScroll: Boolean = true) {
        val scroll = findViewById<androidx.core.widget.NestedScrollView>(R.id.mainScroll)
        if (rememberScroll) sectionScroll[currentSection] = scroll.scrollY
        val local = when (destination) {
            TopLevelNavigation.Destination.BUDGET, TopLevelNavigation.Destination.ANALYSIS -> TopLevelNavigation.Destination.OVERVIEW
            else -> destination
        }
        currentSection = local
        findViewById<View>(R.id.mainOverviewPanel).visibility = if (local == TopLevelNavigation.Destination.OVERVIEW) View.VISIBLE else View.GONE
        findViewById<View>(R.id.mainPlanPanel).visibility = if (local == TopLevelNavigation.Destination.PLAN) View.VISIBLE else View.GONE
        findViewById<View>(R.id.mainMorePanel).visibility = if (local == TopLevelNavigation.Destination.MORE) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.mainScreenTitle).setText(local.titleId)
        TopLevelNavigation.select(this, local)
        if (local == TopLevelNavigation.Destination.PLAN) refreshPlanOverview()
        scroll.post {
            if (currentSection == local) scroll.scrollTo(0, sectionScroll[local] ?: 0)
        }
        when (destination) {
            TopLevelNavigation.Destination.BUDGET -> startActivity(Intent(this, BudgetActivity::class.java))
            TopLevelNavigation.Destination.ANALYSIS -> startActivity(Intent(this, StatisticsActivity::class.java))
            else -> Unit
        }
    }

    override fun onResume() {
        super.onResume()
        refreshHeader()
        BudgetWidgetProvider.updateAll(this)
    }

    private fun refreshHeader() {
        if (currentSection == TopLevelNavigation.Destination.PLAN) refreshPlanOverview()
        refreshRecentTransactions()
        headerRefreshJob?.cancel()
        headerRefreshJob = lifecycleScope.launch {
            val manager = BudgetManager.getInstance(this@MainActivity)
            manager.getCategoriesAsync(forceReload = true)
            val profiles = manager.getBudgetProfilesAsync()
            val activeId = manager.getActiveBudgetId()
            val profile = profiles.firstOrNull { it.id == activeId }
            findViewById<TextView>(R.id.mainProfileNameText).text =
                profile?.name ?: getString(R.string.budget_profiles_default_name)
            findViewById<TextView>(R.id.mainPlanBudgetButton).text = getString(
                R.string.main_plan_budget, profile?.name ?: getString(R.string.budget_profiles_default_name),
            )
            val balance = manager.getTotalBalance(activeId)
        findViewById<View>(R.id.mainBalanceCard).setOnClickListener {
            startActivity(Intent(this@MainActivity, BudgetActivity::class.java))
        }
        findViewById<TextView>(R.id.mainActiveBalanceText).apply {
                text = MoneyFormat.formatRub(balance)
                setTextColor(
                    ContextCompat.getColor(
                        this@MainActivity,
                        if (balance >= 0.0) R.color.main_hero_balance_positive else R.color.main_hero_balance_negative,
                    ),
                )
            }
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
                ObligationUpgradeHelper.runLinkedSyncOnceIfNeeded(
                    this@MainActivity,
                    manager.repository,
                )
                MainDashboardHelper.loadSummary(this@MainActivity, manager)
            }
            if (summary.budgetId != manager.getActiveBudgetId()) return@launch
            bindAttentionSection(summary)
            val safeToSpend = withContext(Dispatchers.IO) {
                MainDashboardHelper.loadSafeToSpend(this@MainActivity, manager, balance)
            }
            bindSafeToSpend(safeToSpend)
            val forecast = withContext(Dispatchers.IO) {
                MainDashboardHelper.loadMonthForecast(manager, balance)
            }
            bindMonthForecast(forecast)
            applyCompactMode()
            bindExpenseShortcuts()
            bindSetupChecklist(manager)
        }
    }

    private suspend fun bindSetupChecklist(manager: BudgetManager) {
        val section = findViewById<View>(R.id.mainSetupChecklistSection)
        if (CompactUiPreferences.shouldHideChecklist(this)) {
            section.visibility = View.GONE
            return
        }
        val transactions = manager.repository.getAllTransactions().first()
        val hasIncomePlan = manager.repository
            .getPlannedIncomeSourcesByBudgetOnce(manager.getActiveBudgetId())
            .any { it.isActive }
        val progress = SetupChecklistHelper.buildProgress(
            transactions = transactions,
            autoBackupEnabled = AutoBackupPreferences.isEnabled(this),
            hasIncomePlan = hasIncomePlan,
            checklistDismissed = SetupChecklistPreferences.isDismissed(this),
        )
        if (!progress.shouldShow) {
            section.visibility = View.GONE
            return
        }
        section.visibility = View.VISIBLE
        findViewById<TextView>(R.id.mainChecklistProgress).text = getString(
            R.string.main_checklist_progress,
            progress.completedCount,
            progress.totalCount,
        )
        val stepsContainer = findViewById<LinearLayout>(R.id.mainChecklistSteps)
        stepsContainer.removeAllViews()
        val padding = resources.getDimensionPixelSize(R.dimen.space_6)
        for (step in progress.steps) {
            val row = TextView(this).apply {
                textSize = 14f
                setPadding(0, padding, 0, padding)
                text = if (step.done) {
                    getString(R.string.main_checklist_step_done, step.title)
                } else {
                    getString(R.string.main_checklist_step_todo, step.title)
                }
                setTextColor(
                    ContextCompat.getColor(
                        this@MainActivity,
                        if (step.done) R.color.income_green else R.color.text_primary,
                    ),
                )
                if (!step.done) {
                    isClickable = true
                    isFocusable = true
                    setOnClickListener { onChecklistStep(step.id) }
                }
            }
            stepsContainer.addView(row)
        }
        findViewById<TextView>(R.id.mainChecklistDismiss).setOnClickListener {
            SetupChecklistPreferences.dismiss(this)
            section.visibility = View.GONE
        }
    }

    private fun onChecklistStep(stepId: SetupChecklistHelper.StepId) {
        val target = when (stepId) {
            SetupChecklistHelper.StepId.FIRST_EXPENSE -> QuickExpenseActivity::class.java
            SetupChecklistHelper.StepId.FIRST_INCOME -> IncomeActivity::class.java
            SetupChecklistHelper.StepId.AUTO_BACKUP -> SettingsActivity::class.java
            SetupChecklistHelper.StepId.INCOME_PLAN -> PlannedIncomeActivity::class.java
        }
        startActivity(Intent(this, target))
    }

    private fun bindExpenseShortcuts() {
        val shortcuts = ExpenseShortcutPreferences.load(this)
        val container = findViewById<LinearLayout>(R.id.mainShortcutsContainer)
        container.removeAllViews()
        val margin = resources.getDimensionPixelSize(R.dimen.space_8)

        if (QuickExpensePreferences.hasRepeatableExpense(this)) {
            container.addView(
                createShortcutChip(getString(R.string.main_shortcut_repeat)) {
                    confirmRepeatLastExpense()
                },
                chipLayoutParams(margin),
            )
        }
        for (shortcut in shortcuts) {
            container.addView(
                createShortcutChip(formatShortcutLabel(shortcut)) {
                    openQuickExpense(shortcut)
                },
                chipLayoutParams(margin),
            )
        }
        container.addView(
            createShortcutChip(getString(R.string.main_shortcut_add)) {
                startActivity(Intent(this, QuickExpenseActivity::class.java))
            },
            chipLayoutParams(margin),
        )
        findViewById<View>(R.id.mainShortcutsScroll).visibility = View.VISIBLE
        findViewById<View>(R.id.mainShortcutsLabel).visibility = View.VISIBLE
    }

    private fun formatShortcutLabel(shortcut: ExpenseShortcut): String {
        return "${shortcut.label} ${MoneyFormat.format(shortcut.amount)}"
    }

    private fun openQuickExpense(shortcut: ExpenseShortcut) {
        startActivity(
            Intent(this, QuickExpenseActivity::class.java)
                .putExtra(QuickExpenseActivity.EXTRA_CATEGORY_ID, shortcut.categoryId)
                .putExtra(QuickExpenseActivity.EXTRA_AMOUNT, shortcut.amount)
                .putExtra(QuickExpenseActivity.EXTRA_LABEL, shortcut.label),
        )
    }

    private fun createShortcutChip(text: String, onClick: () -> Unit): TextView {
        return TextView(this, null, 0, R.style.Chip_MyBudget_Filter).apply {
            this.text = text
            isClickable = true
            isFocusable = true
            setTypeface(null, Typeface.BOLD)
            setOnClickListener { onClick() }
        }
    }

    private fun chipLayoutParams(marginEnd: Int): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { this.marginEnd = marginEnd }
    }

    private fun confirmRepeatLastExpense() {
        val amount = QuickExpensePreferences.getLastAmount(this) ?: return
        val categoryId = QuickExpensePreferences.getLastCategoryId(this)
        if (categoryId <= 0) return
        val description = QuickExpensePreferences.getLastDescription(this).orEmpty()
        lifecycleScope.launch {
            val manager = BudgetManager.getInstance(this@MainActivity)
            val category = manager.getCategoryById(categoryId)
            val label = category?.name ?: getString(R.string.transaction_expense)
            val summary = if (description.isBlank()) label else "$label · $description"
            AlertDialog.Builder(this@MainActivity)
                .setTitle(R.string.main_repeat_confirm_title)
                .setMessage(getString(R.string.main_repeat_confirm_message, MoneyFormat.formatRub(amount), summary))
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    lifecycleScope.launch {
                        manager.recordTransaction(
                            categoryId,
                            amount,
                            "expense",
                            description.ifBlank { getString(R.string.transaction_expense) },
                        )
                        Toast.makeText(this@MainActivity, R.string.main_repeat_done, Toast.LENGTH_SHORT).show()
                        refreshHeader()
                    }
                }
                .setNeutralButton(R.string.quick_expense_full_form) { _, _ ->
                    startActivity(Intent(this@MainActivity, QuickExpenseActivity::class.java))
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun refreshPlanOverview() {
        planRefreshJob?.cancel()
        planRefreshJob = lifecycleScope.launch {
            val manager = BudgetManager.getInstance(this@MainActivity)
            val budgetId = manager.getActiveBudgetId()
            val month = java.time.YearMonth.now()
            val error = findViewById<TextView>(R.id.mainPlanStatus)
            error.setText(R.string.plan_overview_loading)
            error.visibility = View.VISIBLE
            listOf(R.id.mainPlanIncomePlanned, R.id.mainPlanIncomeReceived, R.id.mainPlanIncomeRemaining,
                R.id.mainPlanExpensePlanned, R.id.mainPlanExpenseSpent).forEach {
                findViewById<TextView>(it).setText(R.string.analysis_value_pending)
            }
            try {
                val summary = withContext(Dispatchers.IO) {
                    manager.database.withTransaction {
                        val dao = manager.database.budgetDao()
                        val (from, to) = MonthlyPlanHelper.monthRangeMs(month.year, month.monthValue)
                        // Linked receipts may be booked on another date but belong to this plan month.
                        val transactions = (dao.getTransactionsInRange(from, to) + dao.getIncomePlanTransactions(budgetId))
                            .distinctBy { it.id }
                        PlanOverviewHelper.summarize(budgetId, month,
                            dao.getAllCategoriesForExport().map { BudgetCategory.fromEntity(it) },
                            dao.getPlannedIncomeSourcesByBudgetOnce(budgetId), dao.getMonthlyIncomePlansForBudget(budgetId),
                            dao.getMonthlyPlansForBudgetMonth(budgetId, month.year, month.monthValue), transactions)
                    }
                }
                if (budgetId != manager.getActiveBudgetId() || month != java.time.YearMonth.now()) return@launch
                findViewById<TextView>(R.id.mainPlanMonth).text = MonthlyPlanHelper.formatMonthLabel(month.year, month.monthValue)
                findViewById<TextView>(R.id.mainPlanIncomePlanned).text = if (summary.hasIncomePlan)
                    MoneyFormat.formatRub(summary.incomePlanned) else getString(R.string.plan_overview_unset)
                findViewById<TextView>(R.id.mainPlanIncomeReceived).text = getString(R.string.plan_overview_received,
                    MoneyFormat.formatRub(summary.incomeReceived)) + if(summary.incomeUnlinked>0.0) "\n"+getString(R.string.income_unlinked_total,MoneyFormat.formatRub(summary.incomeUnlinked)) else ""
                findViewById<TextView>(R.id.mainPlanIncomeRemaining).text = getString(R.string.plan_overview_remaining,
                    MoneyFormat.formatRub(summary.incomeRemaining))
                findViewById<TextView>(R.id.mainPlanExpensePlanned).text = if (summary.hasExpensePlan)
                    MoneyFormat.formatRub(summary.expensePlanned) else getString(R.string.plan_overview_unset)
                findViewById<TextView>(R.id.mainPlanExpenseSpent).text = getString(R.string.plan_overview_spent,
                    MoneyFormat.formatRub(summary.expenseSpent))
                error.visibility = View.GONE
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled
            } catch (_: Exception) {
                error.setText(R.string.plan_overview_error)
            }
        }
    }

    private fun refreshRecentTransactions() {
        recentRefreshJob?.cancel()
        recentRefreshJob = lifecycleScope.launch {
            val manager = BudgetManager.getInstance(this@MainActivity)
            val budgetId = manager.getActiveBudgetId()
            val container = findViewById<LinearLayout>(R.id.mainRecentItems)
            fun showMessage(message: Int) {
                container.removeAllViews()
                container.addView(TextView(this@MainActivity).apply {
                    setText(message)
                    setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                    textSize = 14f
                    val padding = resources.getDimensionPixelSize(R.dimen.space_16)
                    setPadding(padding, padding, padding, padding)
                })
            }
            showMessage(R.string.main_recent_loading)
            val rows = try {
                withContext(Dispatchers.IO) {
                    manager.database.budgetDao().getRecentDashboardTransactions(budgetId, 3)
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (manager.getActiveBudgetId() == budgetId) showMessage(R.string.main_recent_error)
                return@launch
            }
            if (manager.getActiveBudgetId() != budgetId) return@launch
            if (rows.isEmpty()) {
                showMessage(R.string.main_recent_empty)
                return@launch
            }
            container.removeAllViews()
            val dateFormat = java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM)
            for ((index, row) in rows.withIndex()) {
                if (index > 0) container.addView(View(this@MainActivity).apply {
                    setBackgroundColor(ContextCompat.getColor(context, R.color.border_light))
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, resources.displayMetrics.density.toInt().coerceAtLeast(1)))
                val view = layoutInflater.inflate(R.layout.item_dashboard_transaction, container, false)
                val income = row.netAmount >= 0.0
                view.findViewById<TextView>(R.id.recentTitle).text = row.description.ifBlank {
                    getString(when {
                        row.mixedTypes -> R.string.main_recent_transfer
                        income -> R.string.main_primary_income
                        else -> R.string.main_primary_expense
                    })
                }
                view.findViewById<TextView>(R.id.recentSubtitle).text = getString(
                    R.string.main_recent_details,
                    if (row.categoryCount > 1) getString(R.string.main_recent_categories, row.categoryCount) else row.categoryName,
                    dateFormat.format(java.util.Date(row.date)),
                )
                view.findViewById<TextView>(R.id.recentAmount).apply {
                    text = if (row.mixedTypes) getString(R.string.main_recent_transfer_amount, MoneyFormat.formatRub(row.netAmount))
                        else getString(if (income) R.string.main_recent_amount_income else R.string.main_recent_amount_expense,
                            MoneyFormat.formatRub(kotlin.math.abs(row.netAmount)))
                    setTextColor(ContextCompat.getColor(context, when {
                        row.mixedTypes -> R.color.text_primary
                        income -> R.color.income_green
                        else -> R.color.expense_red
                    }))
                }
                container.addView(view)
            }
        }
    }

    private fun bindSafeToSpend(info: SalaryCycleHelper.SafeToSpendInfo?) {
        val view = findViewById<TextView>(R.id.mainSafeToSpendText)
        if (info == null || info.dailyAmount <= 0.0) {
            view.visibility = View.GONE
            return
        }
        view.visibility = View.VISIBLE
        view.text = if (info.isPaydayBased) {
            getString(
                R.string.main_safe_to_spend_payday,
                MoneyFormat.format(info.dailyAmount),
                info.daysUntil,
                info.paydayDateLabel,
                info.incomeSourceName,
            )
        } else {
            getString(
                R.string.budget_safe_to_spend,
                MoneyFormat.format(info.dailyAmount),
                info.daysUntil,
            )
        }
    }

    private fun bindMonthForecast(forecast: MonthForecastHelper.MonthForecast?) {
        val view = findViewById<TextView>(R.id.mainMonthForecastText)
        if (forecast == null) {
            view.visibility = View.GONE
            return
        }
        view.visibility = View.VISIBLE
        view.text = getString(
            R.string.main_month_forecast,
            MoneyFormat.format(forecast.projectedEndBalance),
            forecast.endDateLabel,
        )
    }

    private fun applyCompactMode() {
        val compact = CompactUiPreferences.isEnabled(this)
        val padding = resources.getDimensionPixelSize(
            if (compact) R.dimen.space_12 else R.dimen.space_16,
        )
        findViewById<LinearLayout>(R.id.mainContentRoot).setPadding(padding, padding, padding, padding)
    }

    private fun bindAttentionSection(summary: MainDashboardSummary) {
        val container = findViewById<LinearLayout>(R.id.mainAttentionItems)
        container.removeAllViews()
        fun addRows(target: LinearLayout, items: List<AttentionItem>, onSelected: (AttentionItem) -> Unit) {
            items.forEach { item ->
                val row = layoutInflater.inflate(R.layout.include_main_attention_row, target, false)
                val icon = when (item.target) {
                    AttentionTarget.PAYMENT -> if (item.entry?.isGlobal == true) "🏠" else "📅"
                    AttentionTarget.GOAL -> "🎯"
                    AttentionTarget.BUDGET -> "⚠️"
                    AttentionTarget.DISTRIBUTION -> "⚖️"
                    AttentionTarget.OBLIGATIONS -> "📋"
                    AttentionTarget.INCOME_PLAN -> "💰"
                }
                MenuRowHelper.bindAttention(row, icon, item.line.title, item.line.subtitle) { onSelected(item) }
                target.addView(row)
            }
        }
        addRows(container, summary.items.take(5)) { openAttention(it) }
        findViewById<View>(R.id.mainAttentionSection).visibility =
            if (summary.items.isEmpty()) View.GONE else View.VISIBLE
        findViewById<android.widget.Button>(R.id.mainAttentionShowAll).apply {
            visibility = if (summary.items.size > 5) View.VISIBLE else View.GONE
            text = getString(R.string.attention_show_all_count, summary.items.size)
            setOnClickListener {
                val list = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
                val scroll = android.widget.ScrollView(this@MainActivity).apply { addView(list) }
                val dialog = AlertDialog.Builder(this@MainActivity).setTitle(R.string.main_section_attention)
                    .setView(scroll).setNegativeButton(android.R.string.ok, null).create()
                addRows(list, summary.items) { item -> dialog.dismiss(); openAttention(item) }
                dialog.show()
            }
        }
        findViewById<TextView>(R.id.mainAttentionInformation).apply {
            text = summary.information
            visibility = if (summary.information.isBlank()) View.GONE else View.VISIBLE
        }
    }

    private fun openAttention(item: AttentionItem) {
        when (item.target) {
            AttentionTarget.PAYMENT -> startActivity(Intent(this, PaymentCalendarActivity::class.java)
                .putExtra("attention_event_key", item.entry?.eventKey))
            AttentionTarget.BUDGET -> startActivity(Intent(this, BalanceCoverageActivity::class.java).putExtra("budgetId",BudgetManager.getInstance(this).getActiveBudgetId()))
            AttentionTarget.OBLIGATIONS -> startActivity(Intent(this, PlannedObligationsActivity::class.java))
            AttentionTarget.INCOME_PLAN -> startActivity(Intent(this, PlannedIncomeActivity::class.java))
            AttentionTarget.GOAL -> startActivity(Intent(this, GoalsActivity::class.java)
                .putExtra("attention_goal_id", item.goalId ?: -1))
            AttentionTarget.DISTRIBUTION -> {
                val pending = PendingDistributionPreferences.getPending(this) ?: return
                startActivity(Intent(this, IncomeDistributionActivity::class.java)
                    .putExtra(IncomeDistributionActivity.EXTRA_TOTAL_INCOME, pending.amount)
                    .putExtra(IncomeDistributionActivity.EXTRA_INCOME_NOTE, pending.note)
                    .putExtra(BudgetIntentExtras.PLANNED_INCOME_SOURCE_ID, pending.plannedSourceId ?: 0)
                    .putExtra(BudgetIntentExtras.PLANNED_INCOME_MONTH, pending.plannedMonth)
                    .putExtra(IncomeDistributionActivity.EXTRA_RECEIPT_REQUEST, pending.requestId)
                    .putExtra("income_final",pending.finalReceipt)
                    .putExtra(BudgetIntentExtras.BUDGET_ID, pending.budgetId))
            }
        }
    }

    private fun bindRow(includeId: Int, titleRes: Int, target: Class<*>) {
        val row = findViewById<View>(includeId)
        val drawable = when (includeId) {
            R.id.transactionsButton -> R.drawable.ic_menu_journal
            R.id.incomePlanButton -> R.drawable.ic_menu_income
            R.id.expensePlanButton -> R.drawable.ic_menu_expense
            R.id.goalsButton -> R.drawable.ic_menu_goals
            R.id.paymentCalendarButton, R.id.yearBudgetingButton -> R.drawable.ic_nav_plan
            R.id.recurringButton -> R.drawable.ic_menu_repeat
            R.id.utilitiesButton -> R.drawable.ic_menu_home
            R.id.autoButton -> R.drawable.ic_menu_auto
            R.id.mainFamilyRow -> R.drawable.ic_menu_family
            R.id.mainSettingsRow -> R.drawable.ic_settings
            R.id.mainHelpRow -> R.drawable.ic_menu_help
            else -> R.drawable.ic_menu_journal
        }
        row.findViewById<android.widget.ImageView>(R.id.dashboardRowIcon).setImageResource(drawable)
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

    private fun maybeProcessMeterReadingReminder() {
        lifecycleScope.launch {
            MeterReadingReminderHelper.processReminder(this@MainActivity)
        }
    }
}
