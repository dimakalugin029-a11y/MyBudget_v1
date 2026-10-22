package ru.mybudget.app

import android.content.Context
import kotlinx.coroutines.flow.first
import ru.mybudget.app.data.BudgetDatabase
import ru.mybudget.app.setup.ParticipantPreferences
import ru.mybudget.app.setup.PendingDistributionPreferences
import ru.mybudget.app.setup.OverspendPreferences
import ru.mybudget.app.utilities.PaymentCalendarHelper
import java.time.LocalDate

data class AttentionLine(val title: String, val subtitle: String? = null)
enum class AttentionTarget { PAYMENT, BUDGET, DISTRIBUTION, OBLIGATIONS, INCOME_PLAN, GOAL }
data class AttentionItem(
    val key: String, val line: AttentionLine, val target: AttentionTarget,
    val priority: Int, val epochDay: Long = Long.MAX_VALUE,
    val entry: PaymentCalendarHelper.Entry? = null, val goalId: Int? = null,
)
data class MainDashboardSummary(
    val budgetId: Int,
    val items: List<AttentionItem>,
    val information: String,
    val goalsLine: AttentionLine? = null,
)

object MainDashboardHelper {
    suspend fun loadSummary(context: Context, budgetManager: BudgetManager): MainDashboardSummary {
        val db = BudgetDatabase.getInstance(context)
        val active = budgetManager.getActiveBudgetId()
        val snapshot = PaymentEventLoader.load(context, active, horizonDays = 6)
        val today = snapshot.today.toEpochDay()
        val actions = mutableListOf<AttentionItem>()
        snapshot.entries.filter { !it.isIncome && !it.isForecast }.forEach { event ->
            val status = when {
                event.epochDay < today -> context.getString(R.string.attention_overdue)
                event.epochDay == today -> context.getString(R.string.attention_today)
                else -> context.getString(R.string.attention_soon)
            }
            val subtitle = listOf(status, event.dateLabel, event.amount?.let { MoneyFormat.formatRub(it) }.orEmpty(),
                event.subtitle).filter { it.isNotBlank() }.joinToString(" · ")
            actions += AttentionItem(event.eventKey, AttentionLine(event.title, subtitle), AttentionTarget.PAYMENT,
                if (event.epochDay < today) 0 else if (event.epochDay == today) 1 else 2,
                event.epochDay, entry = event)
        }
        val ids = snapshot.categories.map { it.id }.toSet()
        val plans = db.budgetDao().getMonthlyPlansForBudgetMonth(active, snapshot.today.year, snapshot.today.monthValue).associateBy { it.categoryId }
        val spent = db.budgetDao().getExpenseSumsSince(BudgetPlanHelper.monthStartMillis()).associate { it.categoryId to it.total }
        val parentIds = snapshot.categories.map { it.parentId }.toSet()
        val leaves = snapshot.categories.filter { it.id !in parentIds }
        val threshold = OverspendPreferences.getThresholdPercent(context)
        val limits = leaves.count {
            BudgetPlanHelper.isCategoryOverspent(BudgetCategory.fromEntity(it), spent[it.id] ?: 0.0, threshold,
                MonthlyPlanHelper.effectivePlannedAmount(BudgetCategory.fromEntity(it), plans[it.id]))
        }
        if (limits > 0) actions += AttentionItem("limits:$active", AttentionLine(
            context.getString(R.string.attention_check_limits, limits),
            context.getString(R.string.attention_limits_hint, threshold)), AttentionTarget.BUDGET, 3)
        val pending = PendingDistributionPreferences.getPending(context)
        if (pending != null && pending.budgetId == active && pending.amount > 0.01) {
            actions += AttentionItem("distribution:$active", AttentionLine(
                context.getString(R.string.main_pending_distribution, MoneyFormat.formatRub(pending.amount))),
                AttentionTarget.DISTRIBUTION, 4)
        }
        val noCategory = snapshot.obligations.count { it.categoryId !in ids }
        if (noCategory > 0) actions += AttentionItem("setup:$active", AttentionLine(
            context.getString(R.string.main_attention_plan_setup_title),
            context.getString(R.string.attention_unlinked_count, noCategory)), AttentionTarget.OBLIGATIONS, 5)
        if (snapshot.obligations.isNotEmpty() && snapshot.incomeSources.none { it.isActive }) {
            actions += AttentionItem("income-setup:$active", AttentionLine(
                context.getString(R.string.main_attention_income_plan_title),
                context.getString(R.string.attention_income_setup)), AttentionTarget.INCOME_PLAN, 5)
        }
        val goalLines = mutableListOf<String>()
        for (goal in db.budgetDao().getAllSavingsGoalsForExport().filter { it.isActive && it.categoryId in ids }) {
            val balance = snapshot.categories.firstOrNull { it.id == goal.categoryId }?.currentBalance ?: 0.0
            if (!AttentionRules.goalNeedsAction(balance, goal.targetAmount, goal.deadline, snapshot.today)) continue
            val deadline = goal.deadline ?: continue
            val due = runCatching { LocalDate.parse(deadline) }.getOrNull() ?: continue
            val days = (due.toEpochDay() - today).toInt()
            if (days > 6) continue
            val progress = GoalProgressHelper.progressPercent(balance, goal.targetAmount)
            val status = if (days < 0) context.getString(R.string.attention_overdue)
                else if (days == 0) context.getString(R.string.attention_today) else context.getString(R.string.attention_soon)
            val line = "${goal.name}: $progress% · $status"
            goalLines += line
            actions += AttentionItem("goal:${goal.id}", AttentionLine(goal.name, "$progress% · $status"),
                AttentionTarget.GOAL, if (days < 0) 0 else if (days == 0) 1 else 2, due.toEpochDay(), goalId = goal.id)
        }
        val info = mutableListOf<String>()
        val incomeOverrides = db.budgetDao().getMonthlyIncomePlansForMonth(active, snapshot.today.year, snapshot.today.monthValue)
        val receipts = db.budgetDao().getIncomePlanTransactions(active)
        val month = java.time.YearMonth.from(snapshot.today)
        val progress = snapshot.incomeSources.map { source ->
            IncomePlanAmounts.progress(source, month, incomeOverrides, receipts)
        }
        if (progress.isNotEmpty()) {
            info += context.getString(R.string.income_plan_dashboard, month.toString(),
                MoneyFormat.formatRub(progress.sumOf { it.planned }), MoneyFormat.formatRub(progress.sumOf { it.received }),
                MoneyFormat.formatRub(progress.sumOf { it.remaining }))
        }
        if (snapshot.entries.any { !it.isIncome && !it.isForecast }) {
            info += PaymentEventLoader.totalsText(context, snapshot.entries.filter { !it.isForecast })
        }
        val income = snapshot.entries.filter { it.isIncome }.sumOf { it.amount ?: 0.0 }
        if (income > 0.0) info += context.getString(R.string.attention_expected_income, MoneyFormat.formatRub(income))
        val forecast = PaymentCalendarHelper.forecastTotal(snapshot.entries)
        if (forecast > 0.0) info += context.getString(R.string.attention_forecast_total, MoneyFormat.formatRub(forecast))
        val participant = ParticipantPreferences.getDefaultParticipant(context)
        if (participant.isNotBlank()) {
            val expenses = db.budgetDao().getTransactionsInRange(BudgetPlanHelper.monthStartMillis(), System.currentTimeMillis() + 1)
                .filter { it.categoryId in ids }
            ParticipantFamilyHelper.buildSummary(expenses, BudgetPlanHelper.monthStartMillis(), participant)?.let {
                info += context.getString(R.string.main_family_my_expenses, it.participantName, MoneyFormat.formatRub(it.myExpenseTotal))
            }
        }
        return MainDashboardSummary(active, actions.distinctBy { it.key }
            .sortedWith(compareBy({ it.priority }, { it.epochDay }, { it.key })), info.joinToString("\n"),
            goalLines.takeIf { it.isNotEmpty() }?.let { AttentionLine(context.getString(R.string.main_attention_goals_title), it.joinToString("\n")) })
    }

    suspend fun loadMonthForecast(budgetManager: BudgetManager, totalBalance: Double): MonthForecastHelper.MonthForecast? {
        val inputs = ForecastDataLoader.load(budgetManager, budgetManager.getActiveBudgetId(),
            ForecastHelper.Horizon.QUARTER, ForecastHelper.Scenario.OPTIMISTIC)
        val month = ForecastHelper.build(inputs).months.firstOrNull() ?: return null
        val today = inputs.today
        val daysLeft = (today.lengthOfMonth() - today.dayOfMonth).coerceAtLeast(1)
        return MonthForecastHelper.MonthForecast(month.projectedEndBalance,
            today.withDayOfMonth(today.lengthOfMonth()).format(java.time.format.DateTimeFormatter.ofPattern("d.MM")),
            MoneyFormat.roundMoney(month.net / daysLeft))
    }
    suspend fun loadSafeToSpend(context: Context, budgetManager: BudgetManager, totalBalance: Double): SalaryCycleHelper.SafeToSpendInfo? {
        val snapshot = PaymentEventLoader.load(context, budgetManager.getActiveBudgetId(), horizonDays = 60)
        return SalaryCycleHelper.compute(totalBalance, snapshot.incomeSources,
            snapshot.entries.filterNot { it.isGlobal || it.isForecast }, snapshot.today, useCalendarIncome = true)
            ?: run {
                val end = snapshot.today.withDayOfMonth(snapshot.today.lengthOfMonth()).toEpochDay()
                val committed = PaymentCalendarHelper.weekPaymentTotal(snapshot.entries.filter { it.epochDay <= end })
                SalaryCycleHelper.computeMonthFallback((totalBalance - committed).coerceAtLeast(0.0))
            }
    }
}
