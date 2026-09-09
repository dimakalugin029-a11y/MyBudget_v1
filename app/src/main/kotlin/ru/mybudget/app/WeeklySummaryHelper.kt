package ru.mybudget.app

import android.content.Context
import ru.mybudget.app.data.BudgetDatabase
import ru.mybudget.app.setup.OverspendPreferences
import ru.mybudget.app.utilities.PaymentCalendarHelper
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.util.Calendar
import java.util.Locale

object WeeklySummaryHelper {
    data class Digest(
        val title: String,
        val body: String,
    )

    suspend fun buildDigest(context: Context): Digest? {
        val manager = BudgetManager.getInstance(context)
        val db = BudgetDatabase.getInstance(context)
        val dao = db.budgetDao()
        val utilityDao = db.utilityDao()
        val activeId = manager.getActiveBudgetId()
        val todayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val today = todayFmt.format(Calendar.getInstance().time)
        val endCal = Calendar.getInstance().apply { add(Calendar.DATE, 7) }
        val endStr = todayFmt.format(endCal.time)

        val lines = mutableListOf<String>()

        val incomeSources = dao.getPlannedIncomeSourcesByBudgetOnce(activeId)
        val todayEpoch = LocalDate.now().toEpochDay()
        val nextPayday = PlannedIncomeHelper.occurrencesInHorizon(incomeSources, todayEpoch, 14)
            .firstOrNull { it.epochDay >= todayEpoch }
        if (nextPayday != null) {
            val days = (nextPayday.epochDay - todayEpoch).toInt()
            lines += context.getString(
                R.string.weekly_summary_payday,
                nextPayday.source.name,
                PlannedIncomeHelper.formatOccurrenceDate(nextPayday.epochDay),
                days,
            )
        }

        val obligations = dao.getPlannedObligationsByBudgetOnce(activeId)
        val categories = manager.getCategoriesAsync()
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
            property.id to ru.mybudget.app.setup.UtilityPaymentReminderPreferences.paymentDay(context, property.id)
        }
        val paidObligationPeriods = ObligationPaymentHelper.paidKeys(dao.getObligationPaymentsByBudget(activeId))
        val calendarEntries = PaymentCalendarHelper.buildEntries(
            reminders = dao.getRemindersInRange(today, endStr),
            recurring = dao.getRecurringInRange(today, endStr),
            unpaidUtilityBills = unpaidUtilityBills,
            obligations = obligations,
            plannedIncome = incomeSources,
            categoryNames = categoryNames,
            todayEpochDay = todayEpoch,
            horizonDays = 7,
            utilityPaymentDays = utilityPaymentDays,
            paidObligationPeriods = paidObligationPeriods,
        )
        val weekTotal = PaymentCalendarHelper.weekPaymentTotal(calendarEntries)
        if (calendarEntries.isNotEmpty()) {
            lines += context.resources.getQuantityString(
                R.plurals.weekly_summary_payments,
                calendarEntries.size,
                calendarEntries.size,
                MoneyFormat.formatRub(weekTotal),
            )
        }

        if (OverspendPreferences.isEnabled(context)) {
            val monthStart = BudgetPlanHelper.monthStartMillis()
            val expenseByCategory = dao.getExpenseSumsSince(monthStart).associate { it.categoryId to it.total }
            val threshold = OverspendPreferences.getThresholdPercent(context)
            val overspendCount = categories.count { category ->
                val spent = expenseByCategory[category.id] ?: 0.0
                BudgetPlanHelper.isCategoryOverspent(category, spent, threshold)
            }
            if (overspendCount > 0) {
                lines += context.resources.getQuantityString(
                    R.plurals.weekly_summary_overspend,
                    overspendCount,
                    overspendCount,
                )
            }
        }

        val summary = MainDashboardHelper.loadSummary(context, manager)
        summary.goalsLine?.let { line ->
            lines += line.title
        }

        if (lines.isEmpty()) return null

        return Digest(
            title = context.getString(R.string.weekly_summary_title),
            body = lines.joinToString("\n"),
        )
    }
}
