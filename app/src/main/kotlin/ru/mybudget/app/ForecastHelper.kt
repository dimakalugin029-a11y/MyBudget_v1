package ru.mybudget.app

import ru.mybudget.app.data.MonthlyCategoryPlanEntity
import ru.mybudget.app.data.MonthlyIncomePlanEntity
import ru.mybudget.app.data.PlannedIncomeSourceEntity
import ru.mybudget.app.data.PlannedObligationEntity
import ru.mybudget.app.data.SavingsGoalEntity
import ru.mybudget.app.data.TransactionEntity
import java.time.LocalDate
import java.time.YearMonth

object ForecastHelper {
    enum class Horizon(val months: Int) {
        QUARTER(3),
        HALF_YEAR(6),
        YEAR(12),
    }

    enum class Scenario { OPTIMISTIC, REALISTIC }

    enum class ExpenseSource { PLAN, OBLIGATION, AVG_FACT, GOAL }

    data class IncomeLine(
        val sourceId: Int,
        val name: String,
        val amount: Double,
    )

    data class ExpenseLine(
        val categoryId: Int,
        val name: String,
        val amount: Double,
        val source: ExpenseSource,
    )

    data class ForecastMonth(
        val year: Int,
        val month: Int,
        val incomeLines: List<IncomeLine>,
        val expenseLines: List<ExpenseLine>,
        val totalIncome: Double,
        val totalExpense: Double,
        val mandatoryExpense: Double,
        val variableExpense: Double,
        val goalsExpense: Double,
        val net: Double,
        val projectedEndBalance: Double,
        val isPartial: Boolean = false,
    )

    data class ForecastResult(
        val months: List<ForecastMonth>,
        val totalIncome: Double,
        val totalExpense: Double,
        val totalNet: Double,
        val projectedEndBalance: Double,
    )

    data class Inputs(
        val startBalance: Double,
        val today: LocalDate,
        val horizon: Horizon,
        val scenario: Scenario,
        val incomeSources: List<PlannedIncomeSourceEntity>,
        val obligations: List<PlannedObligationEntity>,
        val monthlyPlans: List<MonthlyCategoryPlanEntity>,
        val pastTransactions: List<TransactionEntity>,
        val goals: List<SavingsGoalEntity>,
        val goalBalances: Map<Int, Double>,
        val categoryNames: Map<Int, String> = emptyMap(),
        val monthlyIncomePlans: List<MonthlyIncomePlanEntity> = emptyList(),
        val includeCurrentMonth: Boolean = false,
        val paidPeriods: Set<ObligationPaymentHelper.PeriodKey> = emptySet(),
    )

    private const val REALISTIC_VARIABLE_FACTOR = 1.1
    private const val VARIABLE_FACT_MONTHS = 3

    private fun appliesInMonth(periodType: String, dueMonth: Int, ym: YearMonth): Boolean {
        return when (periodType) {
            "quarterly" -> ((ym.monthValue - dueMonth.coerceIn(1, 12)) % 12 + 12) % 12 % 3 == 0
            PlannedObligationHelper.PERIOD_YEARLY -> ym.monthValue == dueMonth.coerceIn(1, 12)
            else -> true
        }
    }

    fun build(inputs: Inputs): ForecastResult {
        val currentMonth = YearMonth.from(inputs.today)
        val startMonth = if (inputs.includeCurrentMonth) currentMonth else currentMonth.plusMonths(1)
        val avgFactByCategory = averageFactExpenseByCategory(inputs)
        val avgMonthlyIncome = averageFactMonthlyIncome(inputs)
        val goalCategories = inputs.goals
            .filter { it.isActive && it.categoryId > 0 }
            .map { it.categoryId }
            .toSet()

        var runningBalance = inputs.startBalance - inputs.goalBalances.filterKeys { it in goalCategories }
            .values.sumOf { it.coerceAtLeast(0.0) }
        val goalBalances = inputs.goalBalances.toMutableMap()
        val months = (0 until inputs.horizon.months + if (inputs.includeCurrentMonth) 1 else 0).map { offset ->
            val ym = startMonth.plusMonths(offset.toLong())
            var incomeLines = incomeForMonth(inputs, ym, avgMonthlyIncome)
            val partial = inputs.includeCurrentMonth && ym == currentMonth
            if (partial) {
                val received = inputs.pastTransactions.filter { it.type == "income" && monthOf(it.date) == ym }.sumOf { it.amount }
                val planned = incomeLines.sumOf { it.amount }
                val ratio = if (planned > 0.0) ((planned - received).coerceAtLeast(0.0) / planned) else 0.0
                incomeLines = incomeLines.map { it.copy(amount = MoneyFormat.roundMoney(it.amount * ratio)) }
            }
            val (mandatoryLines, mandatoryTotal) = mandatoryExpenseForMonth(inputs, ym, avgFactByCategory, goalCategories)
            val goalsLines = goalsForMonth(inputs.copy(goalBalances = goalBalances), ym, mandatoryLines)
            (mandatoryLines + goalsLines).filter { it.categoryId in goalCategories }.forEach {
                goalBalances[it.categoryId] = (goalBalances[it.categoryId] ?: 0.0) + it.amount
            }
            val totalIncome = MoneyFormat.roundMoney(incomeLines.sumOf { it.amount })
            val totalExpense = MoneyFormat.roundMoney(mandatoryTotal + goalsLines.sumOf { it.amount })
            val net = MoneyFormat.roundMoney(totalIncome - totalExpense)
            runningBalance = MoneyFormat.roundMoney(runningBalance + net)
            ForecastMonth(
                year = ym.year,
                month = ym.monthValue,
                incomeLines = incomeLines,
                expenseLines = mandatoryLines + goalsLines,
                totalIncome = totalIncome,
                totalExpense = totalExpense,
                mandatoryExpense = MoneyFormat.roundMoney(mandatoryTotal),
                variableExpense = MoneyFormat.roundMoney(
                    mandatoryLines.filter { it.source == ExpenseSource.AVG_FACT }.sumOf { it.amount },
                ),
                goalsExpense = MoneyFormat.roundMoney(goalsLines.sumOf { it.amount }),
                net = net,
                projectedEndBalance = runningBalance,
                isPartial = partial,
            )
        }
        val totalIncome = MoneyFormat.roundMoney(months.sumOf { it.totalIncome })
        val totalExpense = MoneyFormat.roundMoney(months.sumOf { it.totalExpense })
        return ForecastResult(
            months = months,
            totalIncome = totalIncome,
            totalExpense = totalExpense,
            totalNet = MoneyFormat.roundMoney(totalIncome - totalExpense),
            projectedEndBalance = months.lastOrNull()?.projectedEndBalance ?: inputs.startBalance,
        )
    }

    private fun incomeForMonth(
        inputs: Inputs,
        ym: YearMonth,
        avgMonthlyIncome: Double,
    ): List<IncomeLine> {
        val lines = inputs.incomeSources
            .filter { it.isActive }
            .mapNotNull { source ->
                val override = inputs.monthlyIncomePlans.firstOrNull { it.sourceId == source.id && it.year == ym.year && it.month == ym.monthValue }
                val monthly = IncomePlanAmounts.planned(source, ym.monthValue, override)
                if (monthly <= 0.0) return@mapNotNull null
                IncomeLine(source.id, source.name, monthly)
            }
        val plannedTotal = lines.sumOf { it.amount }
        if (inputs.scenario == Scenario.REALISTIC && avgMonthlyIncome > 0.0 && plannedTotal > avgMonthlyIncome) {
            val factor = avgMonthlyIncome / plannedTotal
            return lines.map { it.copy(amount = MoneyFormat.roundMoney(it.amount * factor)) }
        }
        return lines
    }

    private fun mandatoryExpenseForMonth(
        inputs: Inputs,
        ym: YearMonth,
        avgFactByCategory: Map<Int, Double>,
        goalCategories: Set<Int>,
    ): Pair<List<ExpenseLine>, Double> {
        val obligationsByCategory = obligationsMonthlyByCategory(inputs.obligations, ym, inputs.paidPeriods)
        val monthPlans = inputs.monthlyPlans.filter { it.year == ym.year && it.month == ym.monthValue }.associateBy { it.categoryId }
        val plansByCategory = monthPlans.filterValues { it.isEnabled }.mapValues { it.value.plannedAmount }
        val categories = (obligationsByCategory.keys + plansByCategory.keys + avgFactByCategory.keys)
        val lines = categories.mapNotNull { categoryId ->
            if (monthPlans[categoryId]?.isEnabled == false && (obligationsByCategory[categoryId] ?: 0.0) <= 0.0) return@mapNotNull null
            val plan = plansByCategory[categoryId] ?: 0.0
            val obligation = obligationsByCategory[categoryId] ?: 0.0
            val mandatory = maxOf(plan, obligation)
            val amount: Double
            val source: ExpenseSource
            when {
                mandatory > 0.0 -> {
                    amount = mandatory
                    source = if (plan >= obligation && plan > 0.0) ExpenseSource.PLAN else ExpenseSource.OBLIGATION
                }
                categoryId in goalCategories -> return@mapNotNull null
                else -> {
                    val avg = avgFactByCategory[categoryId] ?: return@mapNotNull null
                    amount = if (inputs.scenario == Scenario.REALISTIC) {
                        avg * REALISTIC_VARIABLE_FACTOR
                    } else {
                        avg
                    }
                    if (amount <= 0.0) return@mapNotNull null
                    source = ExpenseSource.AVG_FACT
                }
            }
            val spent = if (inputs.includeCurrentMonth && ym == YearMonth.from(inputs.today)) inputs.pastTransactions
                .filter { it.type == "expense" && it.categoryId == categoryId && monthOf(it.date) == ym }.sumOf { it.amount } else 0.0
            ExpenseLine(
                categoryId = categoryId,
                name = inputs.categoryNames[categoryId].orEmpty(),
                amount = MoneyFormat.roundMoney((amount - spent).coerceAtLeast(0.0)),
                source = source,
            )
        }
        return lines to MoneyFormat.roundMoney(lines.sumOf { it.amount })
    }

    private fun obligationsMonthlyByCategory(
        obligations: List<PlannedObligationEntity>,
        ym: YearMonth,
        paidPeriods: Set<ObligationPaymentHelper.PeriodKey> = emptySet(),
    ): Map<Int, Double> {
        val map = linkedMapOf<Int, Double>()
        obligations.filter { it.isActive && it.categoryId > 0 }.forEach { item ->
            if (ObligationPaymentHelper.PeriodKey(item.id, ym.year, ym.monthValue) in paidPeriods) return@forEach
            val (applies, monthly) = when (item.periodType) {
                PlannedObligationHelper.PERIOD_YEARLY ->
                    appliesInMonth(item.periodType, item.dueMonth, ym) to item.amount
                "quarterly" ->
                    appliesInMonth(item.periodType, item.dueMonth, ym) to item.amount
                else -> true to PlannedObligationHelper.monthlyEquivalent(item)
            }
            if (applies) {
                val current = map[item.categoryId] ?: 0.0
                map[item.categoryId] = current + monthly
            }
        }
        return map
    }

    private fun goalsForMonth(
        inputs: Inputs,
        ym: YearMonth,
        mandatoryLines: List<ExpenseLine>,
    ): List<ExpenseLine> {
        val contributions = mandatoryLines.groupBy { it.categoryId }.mapValues { it.value.sumOf { line -> line.amount } }.toMutableMap()
        val allocated = mutableMapOf<Int, Double>()
        return inputs.goals
            .filter { it.isActive && it.categoryId > 0 }
            .mapNotNull { goal ->
                val deadline = parseDeadline(goal.deadline) ?: return@mapNotNull null
                if (deadline.isBefore(ym.atDay(1))) return@mapNotNull null
                val balance = (inputs.goalBalances[goal.categoryId] ?: 0.0) + (allocated[goal.categoryId] ?: 0.0)
                val remaining = goal.targetAmount - balance
                if (remaining <= 0.0) return@mapNotNull null
                val monthsLeft = java.time.temporal.ChronoUnit.MONTHS.between(ym, YearMonth.from(deadline)) + 1
                val need = remaining / maxOf(1L, monthsLeft)
                val existing = contributions[goal.categoryId] ?: 0.0
                val used = minOf(existing, remaining)
                contributions[goal.categoryId] = existing - used
                val outflow = (need - used).coerceIn(0.0, (remaining - used).coerceAtLeast(0.0))
                allocated[goal.categoryId] = (allocated[goal.categoryId] ?: 0.0) + used + outflow
                if (outflow <= 0.0) return@mapNotNull null
                ExpenseLine(
                    categoryId = goal.categoryId,
                    name = goal.name,
                    amount = MoneyFormat.roundMoney(outflow),
                    source = ExpenseSource.GOAL,
                )
            }
    }

    private fun averageFactExpenseByCategory(inputs: Inputs): Map<Int, Double> {
        val goalCategories = inputs.goals
            .filter { it.isActive && it.categoryId > 0 }
            .map { it.categoryId }
            .toSet()
        val startMonth = YearMonth.from(inputs.today).minusMonths(VARIABLE_FACT_MONTHS.toLong())
        val endMonth = YearMonth.from(inputs.today).minusMonths(1)
        val sums = linkedMapOf<Int, Double>()
        inputs.pastTransactions
            .filter { it.type == "expense" && it.amount > 0.0 }
            .forEach { tx ->
                val ym = monthOf(tx.date) ?: return@forEach
                if (ym >= startMonth && ym <= endMonth && tx.categoryId !in goalCategories) {
                    sums[tx.categoryId] = (sums[tx.categoryId] ?: 0.0) + tx.amount
                }
            }
        return sums.mapValues { (_, total) ->
            MoneyFormat.roundMoney(total / VARIABLE_FACT_MONTHS)
        }.filterValues { it > 0.0 }
    }

    private fun averageFactMonthlyIncome(inputs: Inputs): Double {
        val startMonth = YearMonth.from(inputs.today).minusMonths(VARIABLE_FACT_MONTHS.toLong())
        val endMonth = YearMonth.from(inputs.today).minusMonths(1)
        val byMonth = linkedMapOf<YearMonth, Double>()
        inputs.pastTransactions
            .filter { it.type == "income" && it.amount > 0.0 }
            .forEach { tx ->
                val ym = monthOf(tx.date) ?: return@forEach
                if (ym >= startMonth && ym <= endMonth) {
                    byMonth[ym] = (byMonth[ym] ?: 0.0) + tx.amount
                }
            }
        if (byMonth.isEmpty()) return 0.0
        return MoneyFormat.roundMoney(byMonth.values.sum() / VARIABLE_FACT_MONTHS)
    }

    private fun monthOf(dateMs: Long): YearMonth? {
        return runCatching {
            val instant = java.time.Instant.ofEpochMilli(dateMs)
            YearMonth.from(instant.atZone(java.time.ZoneId.systemDefault()).toLocalDate())
        }.getOrNull()
    }

    private fun parseDeadline(deadline: String?): LocalDate? {
        if (deadline.isNullOrBlank()) return null
        return runCatching { LocalDate.parse(deadline) }.getOrNull()
    }
}
