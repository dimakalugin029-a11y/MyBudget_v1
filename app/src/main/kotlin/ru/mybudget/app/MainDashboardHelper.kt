package ru.mybudget.app

import android.content.Context
import kotlinx.coroutines.flow.first
import ru.mybudget.app.data.BudgetDatabase
import ru.mybudget.app.setup.OverspendPreferences
import ru.mybudget.app.setup.PendingDistributionPreferences
import ru.mybudget.app.setup.UtilityPaymentReminderPreferences
import ru.mybudget.app.utilities.PaymentCalendarHelper
import ru.mybudget.app.utilities.UtilityAttentionHelper
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.util.Calendar
import java.util.Locale

data class AttentionLine(
    val title: String,
    val subtitle: String? = null,
)

data class MainDashboardSummary(
    val overspendLine: AttentionLine? = null,
    val utilitiesLine: AttentionLine? = null,
    val pendingDistributionLine: AttentionLine? = null,
    val incomePlanLine: AttentionLine? = null,
    val planSetupLine: AttentionLine? = null,
    val upcomingPaymentsLine: AttentionLine? = null,
    val goalsLine: AttentionLine? = null,
)

object MainDashboardHelper {
    suspend fun loadSummary(context: Context, budgetManager: BudgetManager): MainDashboardSummary {
        val db = BudgetDatabase.getInstance(context)
        val dao = db.budgetDao()
        val utilityDao = db.utilityDao()
        val todayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val today = todayFmt.format(Calendar.getInstance().time)

        val overspendCount = countOverspentCategories(context, budgetManager)
        val overspendLine = if (overspendCount > 0) {
            AttentionLine(
                context.resources.getQuantityString(R.plurals.main_overspend_summary, overspendCount, overspendCount),
            )
        } else {
            null
        }

        val utilitiesText = UtilityAttentionHelper.buildAttentionLine(context, utilityDao)
        val utilitiesLine = utilitiesText?.let {
            AttentionLine(context.getString(R.string.main_attention_utilities_title), it)
        }

        val activeId = budgetManager.getActiveBudgetId()
        val obligations = dao.getPlannedObligationsByBudgetOnce(activeId)
        val incomeSources = dao.getPlannedIncomeSourcesByBudgetOnce(activeId)
        val incomeMonthly = PlannedIncomeHelper.monthlyTotal(incomeSources)
        val obligationsMonthly = PlannedObligationHelper.totalMonthly(obligations)
        val incomePlanLine = PlannedIncomeHelper.buildDashboardAttention(
            context,
            incomeMonthly,
            obligationsMonthly,
        )
        val planSetupLine = PlannedObligationHelper.buildPlanSetupAttention(
            context,
            obligations,
            incomeSources,
        )

        val pending = PendingDistributionPreferences.getPending(context)
        val pendingDistributionLine = if (pending != null && pending.budgetId == activeId && pending.amount > 0.01) {
            AttentionLine(context.getString(R.string.main_pending_distribution, MoneyFormat.formatRub(pending.amount)))
        } else {
            null
        }

        val endCal = Calendar.getInstance().apply { add(Calendar.DATE, 7) }
        val endStr = todayFmt.format(endCal.time)
        val remindersWeek = dao.getRemindersInRange(today, endStr)
        val recurringWeek = dao.getRecurringInRange(today, endStr)
        val categories = budgetManager.getCategoriesAsync()
        val categoryNames = categories.associate { it.id to it.name }
        val totals = utilityDao.getBillGrandTotals().associate { it.billId to it.total }
        val propertyNames = utilityDao.getAllProperties().associate { it.id to it.name }
        val unpaidUtilityBills = utilityDao.getAllBills().mapNotNull { bill ->
            val total = totals[bill.id] ?: 0.0
            if (bill.budgetPaidAt == null && total > 0.0) {
                PaymentCalendarHelper.UnpaidUtilityBill(
                    bill = bill,
                    total = total,
                    propertyName = propertyNames[bill.propertyId].orEmpty(),
                )
            } else {
                null
            }
        }
        val utilityPaymentDays = utilityDao.getAllProperties().associate { property ->
            property.id to UtilityPaymentReminderPreferences.paymentDay(context, property.id)
        }
        val calendarEntries = PaymentCalendarHelper.buildEntries(
            reminders = remindersWeek,
            recurring = recurringWeek,
            unpaidUtilityBills = unpaidUtilityBills,
            obligations = obligations,
            plannedIncome = incomeSources,
            categoryNames = categoryNames,
            todayEpochDay = LocalDate.now().toEpochDay(),
            horizonDays = 7,
            utilityPaymentDays = utilityPaymentDays,
        )
        val calendarCount = calendarEntries.size
        val weekTotal = PaymentCalendarHelper.weekPaymentTotal(calendarEntries)
        val upcomingPaymentsLine = if (calendarCount > 0) {
            val subtitle = if (weekTotal > 0.0) {
                context.getString(R.string.main_upcoming_week_total, MoneyFormat.formatRub(weekTotal))
            } else {
                null
            }
            AttentionLine(
                context.resources.getQuantityString(
                    R.plurals.main_upcoming_payments_summary,
                    calendarCount,
                    calendarCount,
                ),
                subtitle,
            )
        } else {
            null
        }

        return MainDashboardSummary(
            overspendLine = overspendLine,
            utilitiesLine = utilitiesLine,
            pendingDistributionLine = pendingDistributionLine,
            incomePlanLine = incomePlanLine,
            planSetupLine = planSetupLine,
            upcomingPaymentsLine = upcomingPaymentsLine,
            goalsLine = buildUrgentGoalsLine(context, budgetManager),
        )
    }

    suspend fun loadSafeToSpend(
        context: Context,
        budgetManager: BudgetManager,
        totalBalance: Double,
    ): SalaryCycleHelper.SafeToSpendInfo? {
        val db = BudgetDatabase.getInstance(context)
        val dao = db.budgetDao()
        val utilityDao = db.utilityDao()
        val activeId = budgetManager.getActiveBudgetId()
        val incomeSources = dao.getPlannedIncomeSourcesByBudgetOnce(activeId)
        val hasFixedPayday = incomeSources.any { it.isActive && it.dayOfMonth > 0 }
        if (!hasFixedPayday) {
            return SalaryCycleHelper.computeMonthFallback(totalBalance)
        }

        val todayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val today = LocalDate.now()
        val todayEpoch = today.toEpochDay()
        val nextPayday = PlannedIncomeHelper.occurrencesInHorizon(incomeSources, todayEpoch, 45)
            .firstOrNull { it.epochDay >= todayEpoch }
            ?: return SalaryCycleHelper.computeMonthFallback(totalBalance)
        val horizonDays = (nextPayday.epochDay - todayEpoch).toInt().coerceIn(1, 60)

        val todayStr = todayFmt.format(Calendar.getInstance().time)
        val endCal = Calendar.getInstance().apply { add(Calendar.DATE, horizonDays) }
        val endStr = todayFmt.format(endCal.time)
        val obligations = dao.getPlannedObligationsByBudgetOnce(activeId)
        val categories = budgetManager.getCategoriesAsync()
        val categoryNames = categories.associate { it.id to it.name }
        val totals = utilityDao.getBillGrandTotals().associate { it.billId to it.total }
        val propertyNames = utilityDao.getAllProperties().associate { it.id to it.name }
        val unpaidUtilityBills = utilityDao.getAllBills().mapNotNull { bill ->
            val total = totals[bill.id] ?: 0.0
            if (bill.budgetPaidAt == null && total > 0.0) {
                PaymentCalendarHelper.UnpaidUtilityBill(
                    bill = bill,
                    total = total,
                    propertyName = propertyNames[bill.propertyId].orEmpty(),
                )
            } else {
                null
            }
        }
        val utilityPaymentDays = utilityDao.getAllProperties().associate { property ->
            property.id to UtilityPaymentReminderPreferences.paymentDay(context, property.id)
        }
        val entries = PaymentCalendarHelper.buildEntries(
            reminders = dao.getRemindersInRange(todayStr, endStr),
            recurring = dao.getRecurringInRange(todayStr, endStr),
            unpaidUtilityBills = unpaidUtilityBills,
            obligations = obligations,
            plannedIncome = incomeSources,
            categoryNames = categoryNames,
            todayEpochDay = todayEpoch,
            horizonDays = horizonDays,
            utilityPaymentDays = utilityPaymentDays,
        )
        return SalaryCycleHelper.compute(totalBalance, incomeSources, entries, today)
            ?: SalaryCycleHelper.computeMonthFallback(totalBalance)
    }

    private suspend fun countOverspentCategories(context: Context, budgetManager: BudgetManager): Int {
        if (!OverspendPreferences.isEnabled(context)) return 0
        val threshold = OverspendPreferences.getThresholdPercent(context) / 100.0
        val monthStart = Calendar.getInstance().apply {
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val expenseByCategory = BudgetDatabase.getInstance(context)
            .budgetDao()
            .getExpenseSumsSince(monthStart)
            .associate { it.categoryId to it.total }
        val categories = budgetManager.getCategoriesAsync()
        return categories.count { category ->
            !budgetManager.hasSubcategories(category.id) &&
                category.plannedAmount > 0.0 &&
                (expenseByCategory[category.id] ?: 0.0) >= category.plannedAmount * threshold
        }
    }

    private suspend fun buildUrgentGoalsLine(context: Context, budgetManager: BudgetManager): AttentionLine? {
        val goals = budgetManager.repository.getAllSavingsGoals().first().filter { it.isActive }
        val lines = goals.mapNotNull { goal ->
            val progress = GoalProgressHelper.progressPercent(
                budgetManager.getCategoryBalanceWithSubcategories(goal.categoryId),
                goal.targetAmount,
            )
            val days = GoalProgressHelper.daysUntilDeadline(goal.deadline) ?: return@mapNotNull null
            if (days > 7 && progress >= 20) return@mapNotNull null
            val deadlineLabel = when {
                days == 0 -> context.getString(R.string.goals_deadline_today)
                days > 0 -> context.getString(R.string.goals_days_left, days)
                else -> context.getString(R.string.goals_days_overdue, -days)
            }
            "${goal.name}: $progress% • $deadlineLabel"
        }.take(2)
        val subtitle = lines.joinToString("\n").ifBlank { return null }
        return AttentionLine(context.getString(R.string.main_attention_goals_title), subtitle)
    }
}
