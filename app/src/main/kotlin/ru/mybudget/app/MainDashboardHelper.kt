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

data class MainDashboardSummary(
    val overspendLine: String? = null,
    val utilitiesLine: String? = null,
    val pendingDistributionLine: String? = null,
    val upcomingPaymentsLine: String? = null,
    val goalsLine: String? = null,
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
            context.resources.getQuantityString(R.plurals.main_overspend_summary, overspendCount, overspendCount)
        } else {
            null
        }

        val utilitiesLine = UtilityAttentionHelper.buildAttentionLine(context, utilityDao)

        val activeId = budgetManager.getActiveBudgetId()
        val obligations = dao.getPlannedObligationsByBudgetOnce(activeId)

        val pending = PendingDistributionPreferences.getPending(context)
        val pendingDistributionLine = if (pending != null && pending.budgetId == activeId && pending.amount > 0.01) {
            context.getString(R.string.main_pending_distribution, MoneyFormat.formatRub(pending.amount))
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
        val calendarCount = PaymentCalendarHelper.buildEntries(
            reminders = remindersWeek,
            recurring = recurringWeek,
            unpaidUtilityBills = unpaidUtilityBills,
            obligations = obligations,
            categoryNames = categoryNames,
            todayEpochDay = LocalDate.now().toEpochDay(),
            horizonDays = 7,
            utilityPaymentDays = utilityPaymentDays,
        ).size
        val upcomingPaymentsLine = if (calendarCount > 0) {
            val base = context.resources.getQuantityString(
                R.plurals.main_upcoming_payments_summary,
                calendarCount,
                calendarCount,
            )
            if (obligations.isEmpty()) {
                base
            } else {
                context.getString(
                    R.string.main_upcoming_with_obligations,
                    base,
                    MoneyFormat.formatRub(PlannedObligationHelper.totalMonthly(obligations)),
                )
            }
        } else if (obligations.isNotEmpty()) {
            context.getString(
                R.string.main_obligations_summary,
                MoneyFormat.formatRub(PlannedObligationHelper.totalMonthly(obligations)),
                MoneyFormat.formatRub(PlannedObligationHelper.totalPerPaycheck(obligations)),
            )
        } else {
            null
        }

        return MainDashboardSummary(
            overspendLine = overspendLine,
            utilitiesLine = utilitiesLine,
            pendingDistributionLine = pendingDistributionLine,
            upcomingPaymentsLine = upcomingPaymentsLine,
            goalsLine = buildUrgentGoalsLine(context, budgetManager),
        )
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

    private suspend fun buildUrgentGoalsLine(context: Context, budgetManager: BudgetManager): String? {
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
        return lines.joinToString("\n").ifBlank { null }
    }
}
