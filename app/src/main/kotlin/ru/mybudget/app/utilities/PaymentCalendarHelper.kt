package ru.mybudget.app.utilities

import ru.mybudget.app.ObligationPaymentHelper
import ru.mybudget.app.PlannedIncomeHelper
import ru.mybudget.app.PlannedObligationHelper
import ru.mybudget.app.data.PaymentReminderEntity
import ru.mybudget.app.data.PlannedIncomeSourceEntity
import ru.mybudget.app.data.PlannedObligationEntity
import ru.mybudget.app.data.RecurringTransactionEntity
import ru.mybudget.app.data.UtilityBillEntity
import ru.mybudget.app.setup.UtilityPaymentReminderPreferences
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter

object PaymentCalendarHelper {
    enum class EntryKind {
        INCOME,
        REMINDER,
        RECURRING,
        UTILITY,
        UTILITY_FORECAST,
        OBLIGATION,
    }

    data class SourceRef(
        val incomeSourceId: Int? = null,
        val reminderId: Int? = null,
        val recurringId: Int? = null,
        val billId: Int? = null,
        val obligationId: Int? = null,
        val propertyId: Int? = null,
    )

    data class UnpaidUtilityBill(
        val bill: UtilityBillEntity,
        val total: Double,
        val propertyName: String,
    )

    data class Entry(
        val epochDay: Long,
        val dateLabel: String,
        val title: String,
        val subtitle: String,
        val amount: Double?,
        val kind: EntryKind,
        val categoryId: Int = 0,
        val sourceRef: SourceRef = SourceRef(),
        val isIncome: Boolean = kind == EntryKind.INCOME,
        val isGlobal: Boolean = false,
    ) {
        val isForecast: Boolean get() = kind == EntryKind.UTILITY_FORECAST
        val eventKey: String get() = when {
            sourceRef.obligationId != null -> "obligation:${sourceRef.obligationId}:${YearMonth.from(LocalDate.ofEpochDay(epochDay))}"
            sourceRef.billId != null -> "bill:${sourceRef.billId}"
            sourceRef.reminderId != null -> "reminder:${sourceRef.reminderId}:$epochDay"
            sourceRef.recurringId != null -> "recurring:${sourceRef.recurringId}:$epochDay"
            sourceRef.incomeSourceId != null -> "income:${sourceRef.incomeSourceId}:$epochDay"
            else -> "$kind:${sourceRef.propertyId}:$epochDay:$title"
        }
    }

    private val isoFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val displayFmt = DateTimeFormatter.ofPattern("dd.MM.yyyy")

    fun buildEntries(
        reminders: List<PaymentReminderEntity>,
        recurring: List<RecurringTransactionEntity>,
        unpaidUtilityBills: List<UnpaidUtilityBill>,
        obligations: List<PlannedObligationEntity>,
        plannedIncome: List<PlannedIncomeSourceEntity> = emptyList(),
        categoryNames: Map<Int, String>,
        todayEpochDay: Long,
        horizonDays: Int = 60,
        utilityPaymentDays: Map<Int, Int> = emptyMap(),
        paidObligationPeriods: Set<ObligationPaymentHelper.PeriodKey> = emptySet(),
        utilityForecasts: List<UtilityCalendarForecastHelper.ForecastEntry> = emptyList(),
    ): List<Entry> {
        val maxDay = todayEpochDay + horizonDays
        val today = LocalDate.ofEpochDay(todayEpochDay)
        val horizonEnd = LocalDate.ofEpochDay(maxDay)
        val result = mutableListOf<Entry>()
        val knownObligations = obligations.map { it.id }.toSet()

        for (reminder in reminders) {
            if (!reminder.isActive || reminder.obligationId in knownObligations) continue
            val epoch = parseDateEpochDay(reminder.dueDate) ?: continue
            if (epoch > maxDay) continue
            val category = categoryNames[reminder.categoryId]
            result += Entry(
                epochDay = epoch,
                dateLabel = formatDisplayDate(reminder.dueDate),
                title = reminder.title,
                subtitle = if (category == null) "" else "Подстатья: $category",
                amount = reminder.amount,
                kind = EntryKind.REMINDER,
                categoryId = reminder.categoryId,
                sourceRef = SourceRef(reminderId = reminder.id, obligationId = reminder.obligationId),
            )
        }

        for (item in recurring) {
            if (!item.isActive || item.obligationId in knownObligations) continue
            val epoch = parseDateEpochDay(item.nextDueDate) ?: continue
            if (epoch > maxDay) continue
            val title = item.description.ifBlank { "Повторяющаяся операция" }
            val category = categoryNames[item.categoryId]
            result += Entry(
                epochDay = epoch,
                dateLabel = formatDisplayDate(item.nextDueDate),
                title = title,
                subtitle = if (category == null) "" else "Подстатья: $category",
                amount = item.amount,
                kind = EntryKind.RECURRING,
                categoryId = item.categoryId,
                sourceRef = SourceRef(recurringId = item.id, obligationId = item.obligationId),
                isIncome = item.type == "income",
            )
        }

        addUtilityPaymentEntries(
            result = result,
            unpaidBills = unpaidUtilityBills,
            utilityPaymentDays = utilityPaymentDays,
            today = today,
            horizonEnd = horizonEnd,
            todayEpochDay = todayEpochDay,
            maxDay = maxDay,
        )
        addUtilityForecastEntries(
            result = result,
            forecasts = utilityForecasts,
            todayEpochDay = todayEpochDay,
            maxDay = maxDay,
        )

        for (ob in obligations.filter { it.isActive }) {
            val created = java.time.Instant.ofEpochMilli(ob.createdAt)
                .atZone(java.time.ZoneId.systemDefault()).toLocalDate()
            // Explicit linked dates preserve outstanding occurrences after the schedule has moved.
            val linkedDates = reminders.filter { it.obligationId == ob.id }.mapNotNull { parseDateEpochDay(it.dueDate) } +
                recurring.filter { it.obligationId == ob.id }.mapNotNull { parseDateEpochDay(it.nextDueDate) }
            val savedStart = ru.mybudget.app.PaymentTracking.startMonth(ob, today).atDay(1)
            val start = minOf(savedStart, linkedDates.minOrNull()?.let { LocalDate.ofEpochDay(it) } ?: savedStart)
            val linkedPeriods = linkedDates.map { YearMonth.from(LocalDate.ofEpochDay(it)) }.toSet()
            if (ob.periodType == PlannedObligationHelper.PERIOD_YEARLY) {
                for (year in start.year..horizonEnd.year) {
                    val ym = YearMonth.of(year, ob.dueMonth.coerceIn(1, 12))
                    val dueDate = PlannedObligationHelper.dueLocalDate(ym, ob.dueDay)
                    val epoch = dueDate.toEpochDay()
                    if (epoch <= maxDay && (dueDate >= savedStart || YearMonth.from(dueDate) in linkedPeriods) &&
                        (dueDate >= created || YearMonth.from(dueDate) in linkedPeriods || dueDate >= today)) {
                        val key = ObligationPaymentHelper.periodKey(ob.id, dueDate)
                        if (!paidObligationPeriods.contains(key)) {
                            addObligationEntry(result, ob, dueDate, categoryNames)
                        }
                    }
                }
            } else {
                var ym = YearMonth.from(start)
                val endYm = YearMonth.from(horizonEnd)
                while (!ym.isAfter(endYm)) {
                    val dueDate = PlannedObligationHelper.dueLocalDate(ym, ob.dueDay)
                    val epoch = dueDate.toEpochDay()
                    if (epoch <= maxDay && (dueDate >= savedStart || YearMonth.from(dueDate) in linkedPeriods) &&
                        (dueDate >= created || YearMonth.from(dueDate) in linkedPeriods || dueDate >= today)) {
                        val key = ObligationPaymentHelper.periodKey(ob.id, dueDate)
                        if (!paidObligationPeriods.contains(key)) {
                            addObligationEntry(result, ob, dueDate, categoryNames)
                        }
                    }
                    ym = ym.plusMonths(1)
                }
            }
        }

        addPlannedIncomeEntries(
            result = result,
            plannedIncome = plannedIncome,
            todayEpochDay = todayEpochDay,
            horizonDays = horizonDays,
        )

        return dedupeOverlappingEntries(
            result.sortedWith(compareBy({ it.epochDay }, { it.title })),
        )
    }

    private fun addUtilityPaymentEntries(
        result: MutableList<Entry>,
        unpaidBills: List<UnpaidUtilityBill>,
        utilityPaymentDays: Map<Int, Int>,
        today: LocalDate,
        horizonEnd: LocalDate,
        todayEpochDay: Long,
        maxDay: Long,
    ) {
        for (unpaid in unpaidBills.filter { it.total > 0.0 }) {
            val bill = unpaid.bill
            val ym = YearMonth.of(bill.year, bill.month)
            val due = PlannedObligationHelper.dueLocalDate(ym,
                utilityPaymentDays[bill.propertyId] ?: UtilityPaymentReminderPreferences.DEFAULT_DAY)
            if (due.toEpochDay() > maxDay) continue
            result += Entry(epochDay = due.toEpochDay(), dateLabel = due.format(displayFmt),
                title = "Коммуналка: ${unpaid.propertyName.ifBlank { "Квартира" }}",
                subtitle = "Общее ЖКХ · квитанция ${ym.monthValue}.${ym.year}",
                amount = unpaid.total, kind = EntryKind.UTILITY, isGlobal = true,
                sourceRef = SourceRef(propertyId = bill.propertyId, billId = bill.id))
        }
    }

    private fun addUtilityForecastEntries(
        result: MutableList<Entry>,
        forecasts: List<UtilityCalendarForecastHelper.ForecastEntry>,
        todayEpochDay: Long,
        maxDay: Long,
    ) {
        for (forecast in forecasts) {
            if (forecast.epochDay !in todayEpochDay..maxDay) continue
            val dueDate = LocalDate.ofEpochDay(forecast.epochDay)
            result += Entry(
                epochDay = forecast.epochDay,
                dateLabel = dueDate.format(displayFmt),
                title = "Коммуналка (прогноз): ${forecast.propertyName}",
                subtitle = "По среднему за прошлые месяцы",
                amount = forecast.forecastTotal,
                kind = EntryKind.UTILITY_FORECAST, isGlobal = true,
                sourceRef = SourceRef(propertyId = forecast.propertyId),
            )
        }
    }

    private fun addObligationEntry(
        result: MutableList<Entry>,
        ob: PlannedObligationEntity,
        dueDate: LocalDate,
        categoryNames: Map<Int, String>,
    ) {
        val dateLabel = dueDate.format(displayFmt)
        val isYearly = ob.periodType == PlannedObligationHelper.PERIOD_YEARLY
        val periodHint = if (isYearly) " / год" else " / месяц"
        val sb = StringBuilder()
        categoryNames[ob.categoryId]?.let { sb.append("Статья: $it") }
        if (isYearly) {
            if (sb.isNotEmpty()) sb.append(" · ")
            sb.append("Ежегодный платёж")
        } else if (sb.isNotEmpty()) {
            sb.append(" · Ежемесячно")
        } else {
            sb.append("Ежемесячный платёж")
        }
        result += Entry(
            epochDay = dueDate.toEpochDay(),
            dateLabel = dateLabel,
            title = ob.name + periodHint,
            subtitle = sb.toString(),
            amount = ob.amount,
            kind = EntryKind.OBLIGATION,
            categoryId = ob.categoryId,
            sourceRef = SourceRef(obligationId = ob.id),
        )
    }

    private fun addPlannedIncomeEntries(
        result: MutableList<Entry>,
        plannedIncome: List<PlannedIncomeSourceEntity>,
        todayEpochDay: Long,
        horizonDays: Int,
    ) {
        for (occurrence in PlannedIncomeHelper.occurrencesInHorizon(plannedIncome, todayEpochDay, horizonDays)) {
            val source = occurrence.source
            result += Entry(
                epochDay = occurrence.epochDay,
                dateLabel = PlannedIncomeHelper.formatOccurrenceDate(occurrence.epochDay),
                title = PlannedIncomeHelper.calendarTitle(source),
                subtitle = "",
                amount = source.amount,
                kind = EntryKind.INCOME,
                sourceRef = SourceRef(incomeSourceId = source.id),
            )
        }
    }

    internal data class CalendarDedupeKey(
        val epochDay: Long,
        val categoryId: Int,
        val amount: Double,
    )

    internal fun dedupeOverlappingEntries(entries: List<Entry>): List<Entry> {
        // Only an explicit identity can merge records; matching amounts are not an identity.
        return entries.sortedBy {
            when (it.kind) {
                EntryKind.OBLIGATION -> 0
                EntryKind.RECURRING -> 1
                else -> 2
            }
        }.distinctBy { it.eventKey }.sortedWith(compareBy({ it.epochDay }, { it.title }))
    }

    internal fun calendarDedupeKey(entry: Entry): CalendarDedupeKey {
        return CalendarDedupeKey(
            epochDay = entry.epochDay,
            categoryId = entry.categoryId,
            amount = roundAmount(entry.amount ?: 0.0),
        )
    }

    private fun roundAmount(value: Double): Double = kotlin.math.round(value * 100.0) / 100.0

    fun weekPaymentTotal(entries: List<Entry>, includeGlobal: Boolean = false): Double =
        ru.mybudget.app.MoneyFormat.roundMoney(entries.filter {
            !it.isIncome && !it.isForecast && (includeGlobal || !it.isGlobal) && (it.amount ?: 0.0) > 0.0
        }.sumOf { it.amount ?: 0.0 })

    fun forecastTotal(entries: List<Entry>): Double = ru.mybudget.app.MoneyFormat.roundMoney(
        entries.filter { it.isForecast }.sumOf { it.amount ?: 0.0 })

    fun parseDateEpochDay(dateStr: String): Long? {
        return runCatching { LocalDate.parse(dateStr, isoFmt).toEpochDay() }.getOrNull()
    }

    private fun formatDisplayDate(isoDate: String): String {
        return runCatching { LocalDate.parse(isoDate, isoFmt).format(displayFmt) }.getOrDefault(isoDate)
    }
}
