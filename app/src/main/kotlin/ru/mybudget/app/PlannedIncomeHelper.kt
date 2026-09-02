package ru.mybudget.app

import android.content.Context
import ru.mybudget.app.data.PlannedIncomeSourceEntity
import java.text.DateFormatSymbols
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

object PlannedIncomeHelper {
    const val TYPE_SALARY = "salary"
    const val TYPE_ADVANCE = "advance"
    const val TYPE_BONUS = "bonus"
    const val TYPE_OTHER = "other"

    const val PERIOD_MONTHLY = "monthly"
    const val PERIOD_QUARTERLY = "quarterly"
    const val PERIOD_YEARLY = "yearly"

    private val displayFmt = DateTimeFormatter.ofPattern("dd.MM.yyyy")

    data class Occurrence(
        val source: PlannedIncomeSourceEntity,
        val dueDate: LocalDate,
        val epochDay: Long,
    )

    fun normalizePeriod(periodType: String?): String {
        return when (periodType) {
            PERIOD_QUARTERLY, PERIOD_YEARLY -> periodType
            else -> PERIOD_MONTHLY
        }
    }

    fun isBonus(source: PlannedIncomeSourceEntity): Boolean = source.sourceType == TYPE_BONUS

    fun effectivePeriod(source: PlannedIncomeSourceEntity): String {
        return if (isBonus(source)) normalizePeriod(source.periodType) else PERIOD_MONTHLY
    }

    fun monthlyEquivalent(source: PlannedIncomeSourceEntity): Double {
        if (!isBonus(source)) return source.amount
        return when (effectivePeriod(source)) {
            PERIOD_QUARTERLY -> MoneyFormat.roundMoney(source.amount / 3.0)
            PERIOD_YEARLY -> MoneyFormat.roundMoney(source.amount / 12.0)
            else -> source.amount
        }
    }

    fun monthlyTotal(sources: List<PlannedIncomeSourceEntity>): Double {
        return MoneyFormat.roundMoney(
            sources.filter { it.isActive }.sumOf { monthlyEquivalent(it) },
        )
    }

    fun freeAfterObligations(incomeMonthly: Double, obligationsMonthly: Double): Double {
        return MoneyFormat.roundMoney(incomeMonthly - obligationsMonthly)
    }

    fun typeLabel(context: Context, sourceType: String): String {
        return when (sourceType) {
            TYPE_SALARY -> context.getString(R.string.income_plan_type_salary)
            TYPE_ADVANCE -> context.getString(R.string.income_plan_type_advance)
            TYPE_BONUS -> context.getString(R.string.income_plan_type_bonus)
            else -> context.getString(R.string.income_plan_type_other)
        }
    }

    fun typeFromSpinnerPosition(position: Int): String {
        return when (position) {
            0 -> TYPE_SALARY
            1 -> TYPE_ADVANCE
            2 -> TYPE_BONUS
            else -> TYPE_OTHER
        }
    }

    fun typeSpinnerPosition(sourceType: String): Int {
        return when (sourceType) {
            TYPE_SALARY -> 0
            TYPE_ADVANCE -> 1
            TYPE_BONUS -> 2
            else -> 3
        }
    }

    fun periodLabel(context: Context, periodType: String): String {
        return when (normalizePeriod(periodType)) {
            PERIOD_QUARTERLY -> context.getString(R.string.income_plan_period_quarterly)
            PERIOD_YEARLY -> context.getString(R.string.income_plan_period_yearly)
            else -> context.getString(R.string.income_plan_period_monthly)
        }
    }

    fun periodFromSpinnerPosition(position: Int): String {
        return when (position) {
            1 -> PERIOD_QUARTERLY
            2 -> PERIOD_YEARLY
            else -> PERIOD_MONTHLY
        }
    }

    fun periodSpinnerPosition(periodType: String): Int {
        return when (normalizePeriod(periodType)) {
            PERIOD_QUARTERLY -> 1
            PERIOD_YEARLY -> 2
            else -> 0
        }
    }

    fun dayLabel(context: Context, dayOfMonth: Int): String {
        return if (dayOfMonth <= 0) {
            context.getString(R.string.income_plan_day_flexible)
        } else {
            PlannedObligationHelper.dueDayLabel(context, dayOfMonth)
        }
    }

    fun monthName(context: Context, month: Int): String {
        val names = DateFormatSymbols(Locale("ru")).months
        return names[(month - 1).coerceIn(0, 11)].replaceFirstChar { ch ->
            if (ch.isLowerCase()) ch.titlecase(Locale("ru")) else ch.toString()
        }
    }

    fun scheduleLabel(context: Context, source: PlannedIncomeSourceEntity): String {
        if (source.dayOfMonth <= 0) return dayLabel(context, source.dayOfMonth)
        val day = PlannedObligationHelper.dueDayLabel(context, source.dayOfMonth)
        return when (effectivePeriod(source)) {
            PERIOD_QUARTERLY -> context.getString(
                R.string.income_plan_schedule_quarterly,
                day,
                monthName(context, source.dueMonth),
            )
            PERIOD_YEARLY -> context.getString(
                R.string.income_plan_schedule_yearly,
                day,
                monthName(context, source.dueMonth),
            )
            else -> context.getString(R.string.income_plan_schedule_monthly, day)
        }
    }

    fun amountLine(context: Context, source: PlannedIncomeSourceEntity): String {
        val amount = MoneyFormat.formatRub(source.amount)
        val monthly = MoneyFormat.formatRub(monthlyEquivalent(source))
        return when {
            isBonus(source) && effectivePeriod(source) == PERIOD_QUARTERLY ->
                context.getString(R.string.income_plan_item_amount_quarterly, amount, monthly)
            isBonus(source) && effectivePeriod(source) == PERIOD_YEARLY ->
                context.getString(R.string.income_plan_item_amount_yearly, amount, monthly)
            else -> context.getString(R.string.income_plan_item_amount, amount)
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
            when (effectivePeriod(source)) {
                PERIOD_QUARTERLY -> addQuarterlyOccurrences(result, source, today, todayEpochDay, maxDay, horizonEnd)
                PERIOD_YEARLY -> addYearlyOccurrences(result, source, today, todayEpochDay, maxDay, horizonEnd)
                else -> addMonthlyOccurrences(result, source, today, todayEpochDay, maxDay, horizonEnd)
            }
        }
        return result.sortedBy { it.epochDay }
    }

    private fun addMonthlyOccurrences(
        result: MutableList<Occurrence>,
        source: PlannedIncomeSourceEntity,
        today: LocalDate,
        todayEpochDay: Long,
        maxDay: Long,
        horizonEnd: LocalDate,
    ) {
        var ym = YearMonth.from(today)
        val endYm = YearMonth.from(horizonEnd)
        while (!ym.isAfter(endYm)) {
            addOccurrenceIfInRange(result, source, ym, todayEpochDay, maxDay)
            ym = ym.plusMonths(1)
        }
    }

    private fun addQuarterlyOccurrences(
        result: MutableList<Occurrence>,
        source: PlannedIncomeSourceEntity,
        today: LocalDate,
        todayEpochDay: Long,
        maxDay: Long,
        horizonEnd: LocalDate,
    ) {
        val months = quarterlyMonths(source.dueMonth)
        var ym = YearMonth.from(today)
        val endYm = YearMonth.from(horizonEnd)
        while (!ym.isAfter(endYm)) {
            if (ym.monthValue in months) {
                addOccurrenceIfInRange(result, source, ym, todayEpochDay, maxDay)
            }
            ym = ym.plusMonths(1)
        }
    }

    private fun addYearlyOccurrences(
        result: MutableList<Occurrence>,
        source: PlannedIncomeSourceEntity,
        today: LocalDate,
        todayEpochDay: Long,
        maxDay: Long,
        horizonEnd: LocalDate,
    ) {
        val month = source.dueMonth.coerceIn(1, 12)
        for (year in today.year..horizonEnd.year) {
            addOccurrenceIfInRange(
                result,
                source,
                YearMonth.of(year, month),
                todayEpochDay,
                maxDay,
            )
        }
    }

    private fun addOccurrenceIfInRange(
        result: MutableList<Occurrence>,
        source: PlannedIncomeSourceEntity,
        ym: YearMonth,
        todayEpochDay: Long,
        maxDay: Long,
    ) {
        val dueDate = PlannedObligationHelper.dueLocalDate(ym, source.dayOfMonth)
        val epoch = dueDate.toEpochDay()
        if (epoch in todayEpochDay..maxDay) {
            result += Occurrence(source, dueDate, epoch)
        }
    }

    internal fun quarterlyMonths(startMonth: Int): Set<Int> {
        val start = startMonth.coerceIn(1, 12)
        return (0 until 4).map { ((start - 1 + it * 3) % 12) + 1 }.toSet()
    }

    fun daysUntilOccurrence(source: PlannedIncomeSourceEntity, today: LocalDate = LocalDate.now()): Int? {
        if (!source.isActive || source.dayOfMonth <= 0) return null
        val todayEpoch = today.toEpochDay()
        val startEpoch = todayEpoch - 30
        val horizon = occurrencesInHorizon(listOf(source), startEpoch, horizonDays = 400)
        val closest = horizon.minByOrNull { kotlin.math.abs(it.epochDay - todayEpoch) } ?: return null
        return (closest.epochDay - todayEpoch).toInt()
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
        if (!isBonus(source) || effectivePeriod(source) == PERIOD_MONTHLY) {
            return context.getString(R.string.payment_calendar_income_subtitle, type)
        }
        val period = periodLabel(context, source.periodType)
        return context.getString(R.string.payment_calendar_income_bonus_subtitle, type, period)
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
                freeAmount = freeAfterObligations(monthlyEquivalent(source), load),
            )
        }
    }

    fun buildDashboardAttention(
        context: Context,
        incomeMonthly: Double,
        obligationsMonthly: Double,
    ): AttentionLine? {
        if (incomeMonthly <= 0.0 && obligationsMonthly <= 0.0) return null
        if (incomeMonthly <= 0.0) {
            return AttentionLine(
                context.getString(R.string.main_attention_income_plan_title),
                context.getString(
                    R.string.main_income_plan_obligations_only,
                    MoneyFormat.formatRub(obligationsMonthly),
                ),
            )
        }
        if (obligationsMonthly <= 0.0) {
            return AttentionLine(
                context.getString(R.string.main_attention_income_plan_title),
                context.getString(
                    R.string.main_income_plan_income_only,
                    MoneyFormat.formatRub(incomeMonthly),
                ),
            )
        }
        val free = freeAfterObligations(incomeMonthly, obligationsMonthly)
        return AttentionLine(
            context.getString(R.string.main_attention_income_plan_title),
            context.getString(
                R.string.main_income_plan_summary_detail,
                MoneyFormat.formatRub(incomeMonthly),
                MoneyFormat.formatRub(obligationsMonthly),
                MoneyFormat.formatRub(free),
            ),
        )
    }
}
