package ru.mybudget.app.utilities

import ru.mybudget.app.PlannedObligationHelper
import ru.mybudget.app.data.PaymentReminderEntity
import ru.mybudget.app.data.PlannedObligationEntity
import ru.mybudget.app.data.RecurringTransactionEntity
import ru.mybudget.app.data.UtilityBillEntity
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

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
    private val monthNames = arrayOf(
        "январь", "февраль", "март", "апрель", "май", "июнь",
        "июль", "август", "сентябрь", "октябрь", "ноябрь", "декабрь",
    )

    fun buildEntries(
        reminders: List<PaymentReminderEntity>,
        recurring: List<RecurringTransactionEntity>,
        unpaidBills: List<Pair<UtilityBillEntity, Double>>,
        obligations: List<PlannedObligationEntity>,
        categoryNames: Map<Int, String>,
        todayEpochDay: Long,
        horizonDays: Int = 60,
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

        for ((bill, total) in unpaidBills) {
            if (total <= 0.0) continue
            val epoch = YearMonth.of(bill.year, bill.month).atEndOfMonth().toEpochDay()
            if (epoch !in todayEpochDay..maxDay) continue
            val monthName = monthNames.getOrNull(bill.month - 1) ?: "?"
            val titled = monthName.replaceFirstChar { ch ->
                if (ch.isLowerCase()) ch.titlecase(Locale.getDefault()) else ch.toString()
            }
            val end = YearMonth.of(bill.year, bill.month).atEndOfMonth()
            val dateLabel = String.format(
                Locale.getDefault(),
                "%02d.%02d.%d",
                end.dayOfMonth,
                bill.month,
                bill.year,
            )
            result += Entry(
                epochDay = epoch,
                dateLabel = dateLabel,
                title = "Коммуналка: $titled ${bill.year}",
                subtitle = "Не списано с бюджета",
                amount = total,
                kind = EntryKind.UTILITY,
                sourceRef = SourceRef(billId = bill.id),
            )
        }

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
