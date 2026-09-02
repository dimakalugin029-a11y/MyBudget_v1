package ru.mybudget.app

import android.content.Context
import ru.mybudget.app.data.PlannedObligationEntity
import java.time.LocalDate
import java.time.YearMonth

object PlannedObligationHelper {
    const val PERIOD_MONTHLY = "monthly"
    const val PERIOD_YEARLY = "yearly"

    const val KIND_FILTER_ALL = "all"
    const val KIND_CREDIT = "credit"
    const val KIND_UTILITIES = "utilities"
    const val KIND_SUBSCRIPTION = "subscription"
    const val KIND_OTHER = "other"

    val OBLIGATION_KINDS = listOf(KIND_CREDIT, KIND_UTILITIES, KIND_SUBSCRIPTION, KIND_OTHER)

    fun normalizeKind(kind: String?): String {
        return when (kind) {
            KIND_CREDIT, KIND_UTILITIES, KIND_SUBSCRIPTION, KIND_OTHER -> kind
            else -> KIND_OTHER
        }
    }

    fun kindLabel(context: Context, kind: String): String {
        return when (normalizeKind(kind)) {
            KIND_CREDIT -> context.getString(R.string.obligations_kind_credit)
            KIND_UTILITIES -> context.getString(R.string.obligations_kind_utilities)
            KIND_SUBSCRIPTION -> context.getString(R.string.obligations_kind_subscription)
            else -> context.getString(R.string.obligations_kind_other)
        }
    }

    fun kindFromSpinnerPosition(position: Int): String {
        return OBLIGATION_KINDS.getOrElse(position) { KIND_OTHER }
    }

    fun kindSpinnerPosition(kind: String): Int {
        val normalized = normalizeKind(kind)
        return OBLIGATION_KINDS.indexOf(normalized).coerceAtLeast(0)
    }

    fun filterByKind(
        obligations: List<PlannedObligationEntity>,
        kindFilter: String,
    ): List<PlannedObligationEntity> {
        if (kindFilter == KIND_FILTER_ALL) return obligations
        return obligations.filter { normalizeKind(it.obligationKind) == kindFilter }
    }

    fun dueLocalDate(yearMonth: YearMonth, dueDay: Int): LocalDate {
        if (dueDay <= 0) return yearMonth.atEndOfMonth()
        return yearMonth.atDay(dueDay.coerceIn(1, yearMonth.lengthOfMonth()))
    }

    fun nextDueDate(obligation: PlannedObligationEntity, today: LocalDate = LocalDate.now()): LocalDate {
        if (obligation.periodType == PERIOD_YEARLY) {
            val month = obligation.dueMonth.coerceIn(1, 12)
            var candidate = dueLocalDate(YearMonth.of(today.year, month), obligation.dueDay)
            if (candidate.isBefore(today)) {
                candidate = dueLocalDate(YearMonth.of(today.year + 1, month), obligation.dueDay)
            }
            return candidate
        }
        var ym = YearMonth.from(today)
        var candidate = dueLocalDate(ym, obligation.dueDay)
        if (candidate.isBefore(today)) {
            ym = ym.plusMonths(1)
            candidate = dueLocalDate(ym, obligation.dueDay)
        }
        return candidate
    }

    fun dueDayLabel(context: Context, dueDay: Int): String {
        return if (dueDay <= 0) {
            context.getString(R.string.obligations_due_day_last)
        } else {
            context.getString(R.string.obligations_due_day_number, dueDay)
        }
    }

    fun dueDaySpinnerPosition(dueDay: Int): Int = if (dueDay <= 0) 31 else (dueDay - 1).coerceIn(0, 30)

    fun dueDayFromSpinnerPosition(position: Int): Int = if (position >= 31) 0 else position + 1

    fun isLinkedToIncome(obligation: PlannedObligationEntity): Boolean {
        return obligation.linkedIncomeSourceId != null && obligation.linkedIncomeSourceId > 0
    }

    fun effectivePaychecks(obligation: PlannedObligationEntity): Int {
        return if (isLinkedToIncome(obligation)) 1 else obligation.paychecksPerMonth.coerceAtLeast(1)
    }

    fun perPaycheck(obligation: PlannedObligationEntity): Double {
        val paychecks = effectivePaychecks(obligation)
        val raw = if (obligation.periodType == PERIOD_YEARLY) {
            (obligation.amount / 12.0) / paychecks
        } else {
            obligation.amount / paychecks
        }
        return MoneyFormat.roundMoney(raw)
    }

    fun monthlyEquivalent(obligation: PlannedObligationEntity): Double {
        val raw = if (obligation.periodType == PERIOD_YEARLY) {
            obligation.amount / 12.0
        } else {
            obligation.amount
        }
        return MoneyFormat.roundMoney(raw)
    }

    fun totalMonthly(obligations: List<PlannedObligationEntity>): Double {
        return MoneyFormat.roundMoney(
            obligations.filter { it.isActive }.sumOf { monthlyEquivalent(it) },
        )
    }

    fun monthlyLoadForSource(sourceId: Int, obligations: List<PlannedObligationEntity>): Double {
        return MoneyFormat.roundMoney(
            obligations
                .filter { it.isActive && it.linkedIncomeSourceId == sourceId }
                .sumOf { monthlyEquivalent(it) },
        )
    }

    fun unlinkedMonthlyLoad(obligations: List<PlannedObligationEntity>): Double {
        return MoneyFormat.roundMoney(
            obligations
                .filter { it.isActive && !isLinkedToIncome(it) }
                .sumOf { monthlyEquivalent(it) },
        )
    }

    fun linkedIncomeName(
        obligation: PlannedObligationEntity,
        sources: List<ru.mybudget.app.data.PlannedIncomeSourceEntity>,
    ): String? {
        val sourceId = obligation.linkedIncomeSourceId ?: return null
        return sources.firstOrNull { it.id == sourceId }?.name
    }

    fun totalPerPaycheck(obligations: List<PlannedObligationEntity>): Double {
        return MoneyFormat.roundMoney(obligations.sumOf { perPaycheck(it) })
    }

    fun distributionByCategory(obligations: List<PlannedObligationEntity>): Map<Int, Double> {
        return breakdownByCategory(obligations).mapValues { (_, lines) ->
            MoneyFormat.roundMoney(lines.sumOf { it.perPaycheck })
        }
    }

    data class ObligationCategoryLine(
        val name: String,
        val perPaycheck: Double,
    )

    fun breakdownByCategory(obligations: List<PlannedObligationEntity>): Map<Int, List<ObligationCategoryLine>> {
        val map = linkedMapOf<Int, MutableList<ObligationCategoryLine>>()
        obligations.filter { it.isActive && it.categoryId > 0 }.forEach { item ->
            map.getOrPut(item.categoryId) { mutableListOf() }
                .add(ObligationCategoryLine(item.name, perPaycheck(item)))
        }
        return map
    }

    fun formatBreakdown(context: Context, lines: List<ObligationCategoryLine>): String {
        if (lines.isEmpty()) return ""
        val parts = lines.joinToString(" + ") { line ->
            context.getString(
                R.string.obligations_breakdown_item,
                line.name,
                MoneyFormat.formatRub(line.perPaycheck),
            )
        }
        return context.getString(R.string.default_amounts_obligations_line, parts)
    }

    fun unlinkedCount(obligations: List<PlannedObligationEntity>): Int {
        return obligations.count { it.categoryId <= 0 }
    }

    fun unlinkedIncomeCount(obligations: List<PlannedObligationEntity>): Int {
        return obligations.count { it.isActive && !isLinkedToIncome(it) }
    }

    fun buildPlanSetupAttention(
        context: Context,
        obligations: List<PlannedObligationEntity>,
    ): AttentionLine? {
        val active = obligations.filter { it.isActive }
        if (active.isEmpty()) return null

        val noCategory = unlinkedCount(obligations)
        if (noCategory > 0) {
            return AttentionLine(
                context.getString(R.string.main_attention_plan_setup_title),
                context.resources.getQuantityString(
                    R.plurals.main_plan_setup_no_category,
                    noCategory,
                    noCategory,
                ),
            )
        }

        // «Делить между доходами» хранится как linkedIncomeSourceId = null — это осознанный выбор,
        // а не пропущенная настройка; предупреждать имеет смысл только о платежах без подстатьи.
        return null
    }

    fun monthlyPlanByCategory(obligations: List<PlannedObligationEntity>): Map<Int, Double> {
        val map = linkedMapOf<Int, Double>()
        obligations.filter { it.isActive && it.categoryId > 0 }.forEach { item ->
            val current = map[item.categoryId] ?: 0.0
            map[item.categoryId] = MoneyFormat.roundMoney(current + monthlyEquivalent(item))
        }
        return map
    }

    fun effectivePlan(plannedAmount: Double, obligationMonthly: Double): Double {
        return when {
            plannedAmount > 0.0 -> plannedAmount
            obligationMonthly > 0.0 -> obligationMonthly
            else -> 0.0
        }
    }
}
