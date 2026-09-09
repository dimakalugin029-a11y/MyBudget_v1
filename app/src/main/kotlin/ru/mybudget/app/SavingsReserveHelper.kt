package ru.mybudget.app

import ru.mybudget.app.data.MonthlyCategoryPlanEntity

object SavingsReserveHelper {
    enum class Basis {
        ECONOMY,
        REMAINDER,
        NONE,
    }

    data class Line(
        val candidate: RolloverCandidate,
        val planned: Double,
        val spent: Double,
        val economy: Double,
        val basis: Basis,
    )

    fun computeLine(
        candidate: RolloverCandidate,
        planned: Double,
        spent: Double,
    ): Line {
        val economy = if (planned > 0.01) (planned - spent).coerceAtLeast(0.0) else 0.0
        val basis = when {
            economy > 0.01 && candidate.balance > 0.01 -> Basis.ECONOMY
            candidate.balance > 0.01 -> Basis.REMAINDER
            else -> Basis.NONE
        }
        return Line(
            candidate = candidate,
            planned = planned,
            spent = spent,
            economy = economy,
            basis = basis,
        )
    }

    fun buildLines(
        candidates: List<RolloverCandidate>,
        monthlyPlans: Map<Int, MonthlyCategoryPlanEntity>,
        spentByCategoryId: Map<Int, Double>,
    ): List<Line> {
        return candidates.map { candidate ->
            val category = candidate.category
            val planned = MonthlyPlanHelper.effectivePlannedAmount(category, monthlyPlans[category.id])
            val spent = spentByCategoryId[category.id] ?: 0.0
            computeLine(candidate, planned, spent)
        }
    }

    fun reserveAmount(line: Line, percent: Int): Double {
        if (percent <= 0 || line.basis == Basis.NONE) return 0.0
        val base = when (line.basis) {
            Basis.ECONOMY -> minOf(line.economy, line.candidate.balance)
            Basis.REMAINDER -> line.candidate.balance
            Basis.NONE -> 0.0
        }
        if (base <= 0.01) return 0.0
        return MoneyFormat.roundMoney(base * percent / 100.0)
    }

    fun totalReserve(lines: Iterable<Line>, selectedIds: Set<Int>, percent: Int): Double {
        return MoneyFormat.roundMoney(
            lines.filter { it.candidate.category.id in selectedIds }
                .sumOf { reserveAmount(it, percent) },
        )
    }
}
