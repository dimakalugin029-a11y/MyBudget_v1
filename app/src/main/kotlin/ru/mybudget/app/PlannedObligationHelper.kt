package ru.mybudget.app

import ru.mybudget.app.data.PlannedObligationEntity

object PlannedObligationHelper {
    const val PERIOD_MONTHLY = "monthly"
    const val PERIOD_YEARLY = "yearly"

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
}
