package ru.mybudget.app

import ru.mybudget.app.data.MonthlyCategoryPlanEntity
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
    )

    private const val REALISTIC_VARIABLE_FACTOR = 1.1
    private const val VARIABLE_FACT_MONTHS = 3

    fun build(inputs: Inputs): ForecastResult {
        val startMonth = YearMonth.from(inputs.today).plusMonths(1)
        val avgFactByCategory = averageFactExpenseByCategory(inputs)
        val avgMonthlyIncome = averageFactMonthlyIncome(inputs)
        val goalCategories = inputs.goals
            .filter { it.isActive && it.categoryId > 0 }
            .map { it.categoryId }
            .toSet()

        var runningBalance = inputs.startBalance
        val months = (0 until inputs.horizon.months).map { offset ->
            val ym = startMonth.plusMonths(offset.toLong())
            val incomeLines = incomeForMonth(inputs, ym, avgMonthlyIncome)
            val (mandatoryLines, mandatoryTotal) = mandatoryExpenseForMonth(inputs, ym, avgFactByCategory, goalCategories)
            val goalsLines = goalsForMonth(inputs, ym, mandatoryLines)
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
                val monthly = when (source.periodType) {
                    PlannedObligationHelper.PERIOD_YEARLY ->
                        if (source.dueMonth == ym.monthValue) source.amount else 0.0
                    else -> source.amount
                }
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
        val obligationsByCategory = obligationsMonthlyByCategory(inputs.obligations, ym)
        val plansByCategory = inputs.monthlyPlans
            .filter { it.year == ym.year && it.month == ym.monthValue && it.isEnabled && it.plannedAmount > 0.0 }
            .associate { it.categoryId to it.plannedAmount }
        val categories = (obligationsByCategory.keys + plansByCategory.keys + avgFactByCategory.keys)
        val lines = categories.mapNotNull { categoryId ->
            val plan = plansByCategory[categoryId] ?: 0.0
            val obligation = obligationsByCategory[categoryId] ?: 0.0
            val mandatory = PlannedObligationHelper.effectivePlan(plan, obligation)
            val amount: Double
            val source: ExpenseSource
            when {
                mandatory > 0.0 -> {
                    amount = mandatory
                    source = if (plan > 0.0) ExpenseSource.PLAN else ExpenseSource.OBLIGATION
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
            ExpenseLine(
                categoryId = categoryId,
                name = inputs.categoryNames[categoryId].orEmpty(),
                amount = MoneyFormat.roundMoney(amount),
                source = source,
            )
        }
        return lines to MoneyFormat.roundMoney(lines.sumOf { it.amount })
    }

    private fun obligationsMonthlyByCategory(
        obligations: List<PlannedObligationEntity>,
        ym: YearMonth,
    ): Map<Int, Double> {
        val map = linkedMapOf<Int, Double>()
        obligations.filter { it.isActive && it.categoryId > 0 }.forEach { item ->
            val (applies, monthly) = when (item.periodType) {
                PlannedObligationHelper.PERIOD_YEARLY ->
                    (item.dueMonth == ym.monthValue) to item.amount
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
        val obligationsMonthly = obligationsMonthlyByCategory(inputs.obligations, ym)
        return inputs.goals
            .filter { it.isActive && it.categoryId > 0 }
            .mapNotNull { goal ->
                val deadline = parseDeadline(goal.deadline) ?: return@mapNotNull null
                if (deadline.isBefore(ym.atDay(1))) return@mapNotNull null
                val balance = inputs.goalBalances[goal.categoryId] ?: 0.0
                val remaining = goal.targetAmount - balance
                if (remaining <= 0.0) return@mapNotNull null
                val monthsLeft = java.time.temporal.ChronoUnit.MONTHS.between(ym, YearMonth.from(deadline)) + 1
                val need = remaining / maxOf(1L, monthsLeft)
                val obligationMonthly = obligationsMonthly[goal.categoryId] ?: 0.0
                val outflow = (need - obligationMonthly).coerceIn(0.0, remaining)
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
