package ru.mybudget.app.utilities

import ru.mybudget.app.PlannedObligationHelper
import ru.mybudget.app.data.PaymentReminderEntity
import ru.mybudget.app.data.PlannedObligationEntity
import ru.mybudget.app.data.RecurringTransactionEntity
import ru.mybudget.app.data.UtilityBillEntity
import ru.mybudget.app.setup.UtilityPaymentReminderPreferences
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter

object PaymentCalendarHelper {
    enum class EntryKind {
        REMINDER,
        RECURRING,
        UTILITY,
        OBLIGATION,
    }

    data class SourceRef(
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
        val sourceRef: SourceRef = SourceRef(),
    )

    private val isoFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val displayFmt = DateTimeFormatter.ofPattern("dd.MM.yyyy")

    fun buildEntries(
        reminders: List<PaymentReminderEntity>,
        recurring: List<RecurringTransactionEntity>,
        unpaidUtilityBills: List<UnpaidUtilityBill>,
        obligations: List<PlannedObligationEntity>,
        categoryNames: Map<Int, String>,
        todayEpochDay: Long,
        horizonDays: Int = 60,
        utilityPaymentDays: Map<Int, Int> = emptyMap(),
    ): List<Entry> {
        val maxDay = todayEpochDay + horizonDays
        val today = LocalDate.ofEpochDay(todayEpochDay)
        val horizonEnd = LocalDate.ofEpochDay(maxDay)
        val result = mutableListOf<Entry>()

        for (reminder in reminders) {
            val epoch = parseDateEpochDay(reminder.dueDate) ?: continue
            if (epoch !in todayEpochDay..maxDay) continue
            val category = categoryNames[reminder.categoryId]
            result += Entry(
                epochDay = epoch,
                dateLabel = formatDisplayDate(reminder.dueDate),
                title = reminder.title,
                subtitle = if (category == null) "" else "Подстатья: $category",
                amount = reminder.amount,
                kind = EntryKind.REMINDER,
                sourceRef = SourceRef(reminderId = reminder.id),
            )
        }

        for (item in recurring) {
            val epoch = parseDateEpochDay(item.nextDueDate) ?: continue
            if (epoch !in todayEpochDay..maxDay) continue
            val title = item.description.ifBlank { "Повторяющаяся операция" }
            val category = categoryNames[item.categoryId]
            result += Entry(
                epochDay = epoch,
                dateLabel = formatDisplayDate(item.nextDueDate),
                title = title,
                subtitle = if (category == null) "" else "Подстатья: $category",
                amount = item.amount,
                kind = EntryKind.RECURRING,
                sourceRef = SourceRef(recurringId = item.id),
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

        for (ob in obligations.filter { it.isActive }) {
            if (ob.periodType == PlannedObligationHelper.PERIOD_YEARLY) {
                for (year in today.year..horizonEnd.year) {
                    val ym = YearMonth.of(year, ob.dueMonth.coerceIn(1, 12))
                    val dueDate = PlannedObligationHelper.dueLocalDate(ym, ob.dueDay)
                    val epoch = dueDate.toEpochDay()
                    if (epoch in todayEpochDay..maxDay) {
                        addObligationEntry(result, ob, dueDate, categoryNames)
                    }
                }
            } else {
                var ym = YearMonth.from(today)
                val endYm = YearMonth.from(horizonEnd)
                while (!ym.isAfter(endYm)) {
                    val dueDate = PlannedObligationHelper.dueLocalDate(ym, ob.dueDay)
                    val epoch = dueDate.toEpochDay()
                    if (epoch in todayEpochDay..maxDay) {
                        addObligationEntry(result, ob, dueDate, categoryNames)
                    }
                    ym = ym.plusMonths(1)
                }
            }
        }

        return result.sortedWith(compareBy({ it.epochDay }, { it.title }))
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
        val byProperty = unpaidBills
            .filter { it.total > 0.0 }
            .groupBy { it.bill.propertyId }
        if (byProperty.isEmpty()) return

        for ((propertyId, bills) in byProperty) {
            val paymentDay = utilityPaymentDays[propertyId] ?: UtilityPaymentReminderPreferences.DEFAULT_DAY
            val propertyName = bills.first().propertyName.ifBlank { "Квартира" }
            val totalAmount = bills.sumOf { it.total }
            val oldestBill = bills.minWithOrNull(
                compareBy<UnpaidUtilityBill> { it.bill.year }.thenBy { it.bill.month },
            )?.bill
            val subtitle = utilityUnpaidSubtitle(bills.size)
            var ym = YearMonth.from(today)
            val endYm = YearMonth.from(horizonEnd)
            while (!ym.isAfter(endYm)) {
                val dueDate = PlannedObligationHelper.dueLocalDate(ym, paymentDay)
                val epoch = dueDate.toEpochDay()
                if (epoch in todayEpochDay..maxDay) {
                    result += Entry(
                        epochDay = epoch,
                        dateLabel = dueDate.format(displayFmt),
                        title = "Коммуналка: $propertyName",
                        subtitle = subtitle,
                        amount = totalAmount,
                        kind = EntryKind.UTILITY,
                        sourceRef = SourceRef(propertyId = propertyId, billId = oldestBill?.id),
                    )
                }
                ym = ym.plusMonths(1)
            }
        }
    }

    private fun utilityUnpaidSubtitle(unpaidCount: Int): String {
        return when {
            unpaidCount == 1 -> "1 месяц не списано с бюджета"
            unpaidCount in 2..4 -> "$unpaidCount месяца не списано с бюджета"
            else -> "$unpaidCount месяцев не списано с бюджета"
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
            sourceRef = SourceRef(obligationId = ob.id),
        )
    }

    fun parseDateEpochDay(dateStr: String): Long? {
        return runCatching { LocalDate.parse(dateStr, isoFmt).toEpochDay() }.getOrNull()
    }

    private fun formatDisplayDate(isoDate: String): String {
        return runCatching { LocalDate.parse(isoDate, isoFmt).format(displayFmt) }.getOrDefault(isoDate)
    }
}
