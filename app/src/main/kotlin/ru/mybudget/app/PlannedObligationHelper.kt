package ru.mybudget.app

import android.content.Context
import ru.mybudget.app.data.PlannedObligationEntity
import java.time.LocalDate
import java.time.YearMonth

object PlannedObligationHelper {
    const val PERIOD_MONTHLY = "monthly"
    const val PERIOD_YEARLY = "yearly"

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

    fun perPaycheck(obligation: PlannedObligationEntity): Double {
        val paychecks = obligation.paychecksPerMonth.coerceAtLeast(1)
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
        return MoneyFormat.roundMoney(obligations.sumOf { monthlyEquivalent(it) })
    }

    fun totalPerPaycheck(obligations: List<PlannedObligationEntity>): Double {
        return MoneyFormat.roundMoney(obligations.sumOf { perPaycheck(it) })
    }

    fun distributionByCategory(obligations: List<PlannedObligationEntity>): Map<Int, Double> {
        val map = linkedMapOf<Int, Double>()
        obligations.filter { it.categoryId > 0 }.forEach { item ->
            val current = map[item.categoryId] ?: 0.0
            map[item.categoryId] = MoneyFormat.roundMoney(current + perPaycheck(item))
        }
        return map
    }

    fun unlinkedCount(obligations: List<PlannedObligationEntity>): Int {
        return obligations.count { it.categoryId <= 0 }
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
