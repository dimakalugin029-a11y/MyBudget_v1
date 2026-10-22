package ru.mybudget.app

import android.content.Context
import androidx.room.withTransaction
import ru.mybudget.app.data.*
import ru.mybudget.app.setup.UtilityPaymentReminderPreferences
import ru.mybudget.app.utilities.PaymentCalendarHelper
import ru.mybudget.app.utilities.UtilityCalendarForecastHelper
import java.time.LocalDate

/** Shared snapshot for the dashboard, calendar, digest and available-money calculation. */
object PaymentEventLoader {
    data class Snapshot(
        val budgetId: Int,
        val today: LocalDate,
        val categories: List<BudgetCategoryEntity>,
        val incomeSources: List<PlannedIncomeSourceEntity>,
        val obligations: List<PlannedObligationEntity>,
        val entries: List<PaymentCalendarHelper.Entry>,
    )

    suspend fun load(context: Context, budgetId: Int, horizonDays: Int = 60,
        today: LocalDate = LocalDate.now(), database: BudgetDatabase = BudgetDatabase.getInstance(context)): Snapshot =
        database.withTransaction {
            val dao = database.budgetDao()
            val utilityDao = database.utilityDao()
            val categories = dao.getAllCategoriesForExport().filter { it.isActive && it.budgetId == budgetId }
            val ids = categories.map { it.id }.toSet()
            val obligations = dao.getPlannedObligationsByBudgetOnce(budgetId)
            val obligationIds = obligations.map { it.id }.toSet()
            val through = today.plusDays(horizonDays.toLong()).toString()
            val reminders = dao.getOpenRemindersThrough(through).filter {
                it.categoryId in ids && (it.obligationId == null || it.obligationId in obligationIds)
            }
            val recurring = dao.getOpenRecurringThrough(through).filter {
                it.categoryId in ids && (it.obligationId == null || it.obligationId in obligationIds)
            }
            val sources = dao.getPlannedIncomeSourcesByBudgetOnce(budgetId)
            val properties = utilityDao.getAllProperties()
            val propertyNames = properties.associate { it.id to it.name }
            val bills = utilityDao.getAllBills()
            val totals = utilityDao.getBillGrandTotals().associate { it.billId to it.total }
            val unpaid = bills.filter { it.budgetPaidAt == null && (totals[it.id] ?: 0.0) > 0.0 }.map {
                PaymentCalendarHelper.UnpaidUtilityBill(it, totals.getValue(it.id), propertyNames[it.propertyId].orEmpty())
            }
            val days = properties.associate { it.id to UtilityPaymentReminderPreferences.paymentDay(context, it.id) }
            val forecasts = UtilityCalendarForecastHelper.buildForecasts(properties, bills, totals, today,
                today.plusDays(horizonDays.toLong()), days)
            val paid = ObligationPaymentHelper.paidKeys(dao.getObligationPaymentsByBudget(budgetId))
            val incomePlans = dao.getMonthlyIncomePlansForBudget(budgetId)
            val receipts = dao.getIncomePlanTransactions(budgetId)
            val incomes = mutableListOf<PaymentCalendarHelper.Entry>()
            val end = today.plusDays(horizonDays.toLong())
            var month = java.time.YearMonth.from(today)
            while (!month.isAfter(java.time.YearMonth.from(end))) {
                for (source in sources.filter { it.dayOfMonth > 0 }) {
                    val progress = IncomePlanAmounts.progress(source, month, incomePlans, receipts)
                    val date = month.atDay(source.dayOfMonth.coerceIn(1, month.lengthOfMonth()))
                    if (progress.remaining <= 0.0 || date.isBefore(today) || date.isAfter(end)) continue
                    incomes += PaymentCalendarHelper.Entry(date.toEpochDay(),
                        PlannedIncomeHelper.formatOccurrenceDate(date.toEpochDay()), source.name,
                        context.getString(R.string.income_plan_progress, MoneyFormat.formatRub(progress.planned),
                            MoneyFormat.formatRub(progress.received), MoneyFormat.formatRub(progress.remaining)),
                        progress.remaining, PaymentCalendarHelper.EntryKind.INCOME,
                        sourceRef = PaymentCalendarHelper.SourceRef(incomeSourceId = source.id))
                }
                month = month.plusMonths(1)
            }
            val entries = PaymentCalendarHelper.buildEntries(reminders, recurring, unpaid,
                obligations.filter { it.categoryId in ids }, emptyList(), categories.associate { it.id to it.name },
                today.toEpochDay(), horizonDays, days, paid, forecasts).plus(incomes)
                .sortedWith(compareBy({ it.epochDay }, { it.title }))
            val possibleDuplicates = entries.filter { !it.isIncome && !it.isForecast && !it.isGlobal }
                .groupBy { PaymentCalendarHelper.calendarDedupeKey(it) }
                .filterValues { it.size > 1 }.keys
            val annotatedEntries = entries.map { entry ->
                if (!entry.isIncome && !entry.isForecast && !entry.isGlobal &&
                    PaymentCalendarHelper.calendarDedupeKey(entry) in possibleDuplicates) {
                    entry.copy(subtitle = listOf(entry.subtitle, context.getString(R.string.attention_possible_duplicate))
                        .filter { it.isNotBlank() }.joinToString(" · "))
                } else entry
            }
            Snapshot(budgetId, today, categories, sources, obligations, annotatedEntries)
        }

    fun totalsText(context: Context, entries: List<PaymentCalendarHelper.Entry>): String {
        val budget = PaymentCalendarHelper.weekPaymentTotal(entries)
        val global = PaymentCalendarHelper.weekPaymentTotal(entries.filter { it.isGlobal }, includeGlobal = true)
        val forecast = PaymentCalendarHelper.forecastTotal(entries)
        return buildList {
            add(context.getString(R.string.attention_budget_total, MoneyFormat.formatRub(budget)))
            if (global > 0.0) add(context.getString(R.string.attention_global_total, MoneyFormat.formatRub(global)))
            if (forecast > 0.0) add(context.getString(R.string.attention_forecast_total, MoneyFormat.formatRub(forecast)))
        }.joinToString("\n")
    }
}
