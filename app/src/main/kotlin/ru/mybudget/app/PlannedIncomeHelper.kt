package ru.mybudget.app

import android.content.Context
import ru.mybudget.app.data.PlannedIncomeSourceEntity
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter

object PlannedIncomeHelper {
    const val TYPE_SALARY = "salary"
    const val TYPE_ADVANCE = "advance"
    const val TYPE_OTHER = "other"

    private val displayFmt = DateTimeFormatter.ofPattern("dd.MM.yyyy")

    data class Occurrence(
        val source: PlannedIncomeSourceEntity,
        val dueDate: LocalDate,
        val epochDay: Long,
    )

    fun monthlyTotal(sources: List<PlannedIncomeSourceEntity>): Double {
        return MoneyFormat.roundMoney(
            sources.filter { it.isActive }.sumOf { it.amount },
        )
    }

    fun freeAfterObligations(incomeMonthly: Double, obligationsMonthly: Double): Double {
        return MoneyFormat.roundMoney(incomeMonthly - obligationsMonthly)
    }

    fun typeLabel(context: Context, sourceType: String): String {
        return when (sourceType) {
            TYPE_SALARY -> context.getString(R.string.income_plan_type_salary)
            TYPE_ADVANCE -> context.getString(R.string.income_plan_type_advance)
            else -> context.getString(R.string.income_plan_type_other)
        }
    }

    fun typeFromSpinnerPosition(position: Int): String {
        return when (position) {
            0 -> TYPE_SALARY
            1 -> TYPE_ADVANCE
            else -> TYPE_OTHER
        }
    }

    fun typeSpinnerPosition(sourceType: String): Int {
        return when (sourceType) {
            TYPE_SALARY -> 0
            TYPE_ADVANCE -> 1
            else -> 2
        }
    }

    fun dayLabel(context: Context, dayOfMonth: Int): String {
        return if (dayOfMonth <= 0) {
            context.getString(R.string.income_plan_day_flexible)
        } else {
            PlannedObligationHelper.dueDayLabel(context, dayOfMonth)
        }
    }

    fun daySpinnerPosition(dayOfMonth: Int): Int = PlannedObligationHelper.dueDaySpinnerPosition(dayOfMonth)

    fun dayFromSpinnerPosition(position: Int): Int = PlannedObligationHelper.dueDayFromSpinnerPosition(position)

    fun occurrencesInHorizon(
        sources: List<PlannedIncomeSourceEntity>,
        todayEpochDay: Long,
        horizonDays: Int = 60,
    ): List<Occurrence> {
        val today = LocalDate.ofEpochDay(todayEpochDay)
        val maxDay = todayEpochDay + horizonDays
        val horizonEnd = LocalDate.ofEpochDay(maxDay)
        val result = mutableListOf<Occurrence>()

        for (source in sources.filter { it.isActive && it.dayOfMonth > 0 }) {
            var ym = YearMonth.from(today)
            val endYm = YearMonth.from(horizonEnd)
            while (!ym.isAfter(endYm)) {
                val dueDate = PlannedObligationHelper.dueLocalDate(ym, source.dayOfMonth)
                val epoch = dueDate.toEpochDay()
                if (epoch in todayEpochDay..maxDay) {
                    result += Occurrence(source, dueDate, epoch)
                }
                ym = ym.plusMonths(1)
            }
        }
        return result.sortedBy { it.epochDay }
    }

    fun daysUntilOccurrence(source: PlannedIncomeSourceEntity, today: LocalDate = LocalDate.now()): Int? {
        if (!source.isActive || source.dayOfMonth <= 0) return null
        val thisMonthDate = PlannedObligationHelper.dueLocalDate(YearMonth.from(today), source.dayOfMonth)
        val thisDiff = (thisMonthDate.toEpochDay() - today.toEpochDay()).toInt()
        val nextMonthDate = PlannedObligationHelper.dueLocalDate(
            YearMonth.from(today).plusMonths(1),
            source.dayOfMonth,
        )
        val nextDiff = (nextMonthDate.toEpochDay() - today.toEpochDay()).toInt()
        return if (kotlin.math.abs(thisDiff) <= kotlin.math.abs(nextDiff)) thisDiff else nextDiff
    }

    fun suggestionsForIncomeEntry(
        sources: List<PlannedIncomeSourceEntity>,
        today: LocalDate = LocalDate.now(),
        windowDays: Int = 5,
    ): List<PlannedIncomeSourceEntity> {
        return sources
            .mapNotNull { source ->
                val days = daysUntilOccurrence(source, today) ?: return@mapNotNull null
                if (kotlin.math.abs(days) <= windowDays) days to source else null
            }
            .sortedWith(compareBy({ kotlin.math.abs(it.first) }, { it.first }))
            .map { it.second }
    }

    fun calendarSubtitle(context: Context, source: PlannedIncomeSourceEntity): String {
        val type = typeLabel(context, source.sourceType)
        return context.getString(R.string.payment_calendar_income_subtitle, type)
    }

    fun calendarTitle(source: PlannedIncomeSourceEntity): String {
        return source.name
    }

    fun formatOccurrenceDate(epochDay: Long): String {
        return LocalDate.ofEpochDay(epochDay).format(displayFmt)
    }

    data class SourceBalance(
        val source: PlannedIncomeSourceEntity,
        val linkedObligationsMonthly: Double,
        val freeAmount: Double,
    )

    fun balanceBySource(
        sources: List<PlannedIncomeSourceEntity>,
        obligations: List<ru.mybudget.app.data.PlannedObligationEntity>,
    ): List<SourceBalance> {
        return sources.filter { it.isActive }.map { source ->
            val load = PlannedObligationHelper.monthlyLoadForSource(source.id, obligations)
            SourceBalance(
                source = source,
                linkedObligationsMonthly = load,
                freeAmount = freeAfterObligations(source.amount, load),
            )
        }
    }

    fun buildDashboardLine(
        context: Context,
        incomeMonthly: Double,
        obligationsMonthly: Double,
    ): String? {
        if (incomeMonthly <= 0.0 && obligationsMonthly <= 0.0) return null
        if (incomeMonthly <= 0.0) {
            return context.getString(
                R.string.main_income_plan_obligations_only,
                MoneyFormat.formatRub(obligationsMonthly),
            )
        }
        if (obligationsMonthly <= 0.0) {
            return context.getString(
                R.string.main_income_plan_income_only,
                MoneyFormat.formatRub(incomeMonthly),
            )
        }
        val free = freeAfterObligations(incomeMonthly, obligationsMonthly)
        return context.getString(
            R.string.main_income_plan_summary,
            MoneyFormat.formatRub(incomeMonthly),
            MoneyFormat.formatRub(obligationsMonthly),
            MoneyFormat.formatRub(free),
        )
    }
}
