package ru.mybudget.app

import ru.mybudget.app.data.MonthlyCategoryPlanEntity
import ru.mybudget.app.data.MonthlyIncomePlanEntity
import ru.mybudget.app.data.PlannedIncomeSourceEntity
import ru.mybudget.app.data.PlannedObligationEntity
import ru.mybudget.app.data.TransactionEntity

object YearBudgetingHelper {

    data class IncomeLine(
        val sourceId: Int,
        val name: String,
        val amount: Double,
        val isOverride: Boolean,
    )

    data class ExpenseLine(
        val categoryId: Int,
        val name: String,
        val amount: Double,
    )

    data class MonthFact(
        val income: Double,
        val expense: Double,
    )

    data class MonthPlan(
        val year: Int,
        val month: Int,
        val incomeLines: List<IncomeLine>,
        val expenseLines: List<ExpenseLine>,
        val incomeTotal: Double,
        val expenseTotal: Double,
        val net: Double,
        val fact: MonthFact?,
    ) {
        val incomeDelta: ForecastVsFactHelper.Delta?
            get() = fact?.let { ForecastVsFactHelper.delta(incomeTotal, it.income) }

        val expenseDelta: ForecastVsFactHelper.Delta?
            get() = fact?.let { ForecastVsFactHelper.delta(expenseTotal, it.expense) }

        val netDelta: ForecastVsFactHelper.Delta?
            get() = fact?.let { ForecastVsFactHelper.delta(net, it.income - it.expense) }

        fun expenseFactByCategory(): Map<Int, Double> {
            val fact = fact ?: return emptyMap()
            val totalByLine = expenseLines.sumOf { it.amount }
            if (totalByLine <= 0.0) return emptyMap()
            return expenseLines.mapNotNull { line ->
                val share = line.amount / totalByLine
                val value = MoneyFormat.roundMoney(fact.expense * share)
                if (value > 0.0) line.categoryId to value else null
            }.toMap()
        }
    }

    data class QuarterPlan(
        val quarter: Int,
        val incomeTotal: Double,
        val expenseTotal: Double,
        val net: Double,
    )

    data class YearPlan(
        val year: Int,
        val months: List<MonthPlan>,
        val quarters: List<QuarterPlan>,
        val incomeTotal: Double,
        val expenseTotal: Double,
        val net: Double,
        val factByCategory: Map<Int, Double> = emptyMap(),
    )

    data class CategoryYearSummary(
        val categoryId: Int,
        val name: String,
        val planTotal: Double,
        val factTotal: Double,
        val remaining: Double,
    )

    fun categoryYearSummaries(
        months: List<MonthPlan>,
        factByCategory: Map<Int, Double>,
        nameById: Map<Int, String>,
    ): List<CategoryYearSummary> {
        val planById = mutableMapOf<Int, Double>()
        months.forEach { month ->
            month.expenseLines.forEach { line ->
                planById[line.categoryId] = (planById[line.categoryId] ?: 0.0) + line.amount
            }
        }
        return (planById.keys + factByCategory.keys)
            .map { id ->
                val plan = MoneyFormat.roundMoney(planById[id] ?: 0.0)
                val fact = MoneyFormat.roundMoney(factByCategory[id] ?: 0.0)
                CategoryYearSummary(
                    categoryId = id,
                    name = nameById[id] ?: id.toString(),
                    planTotal = plan,
                    factTotal = fact,
                    remaining = MoneyFormat.roundMoney(plan - fact),
                )
            }
            .filter { it.planTotal != 0.0 || it.factTotal != 0.0 }
            .sortedWith(
                compareByDescending<CategoryYearSummary> { it.planTotal }
                    .thenByDescending { it.factTotal }
                    .thenBy { it.name },
            )
    }

    fun monthKeys(year: Int): List<Pair<Int, Int>> = (1..12).map { year to it }

    fun incomeLineFor(
        source: PlannedIncomeSourceEntity,
        overrides: Map<Int, MonthlyIncomePlanEntity>,
        month: Int = 1,
    ): IncomeLine? {
        if (!source.isActive) return null
        val override = overrides[source.id]
        if (override != null) {
            if (!override.isEnabled) return null
            return IncomeLine(
                sourceId = source.id,
                name = source.name,
                amount = MoneyFormat.roundMoney(override.amount),
                isOverride = true,
            )
        }
        val equivalent = PlannedIncomeHelper.budgetMonthAmount(source, month)
        if (equivalent <= 0.0) return null
        return IncomeLine(
            sourceId = source.id,
            name = source.name,
            amount = MoneyFormat.roundMoney(equivalent),
            isOverride = false,
        )
    }

    fun expenseLineFor(
        category: BudgetCategory,
        plan: MonthlyCategoryPlanEntity?,
        obligationMonthly: Double = 0.0,
    ): ExpenseLine? {
        if (!category.isActive) return null
        val amount = if (plan != null) {
            MonthlyPlanHelper.effectivePlannedAmount(category, plan)
        } else {
            PlannedObligationHelper.effectivePlan(
                MonthlyPlanHelper.effectivePlannedAmount(category, null),
                obligationMonthly,
            )
        }
        if (amount <= 0.0) return null
        return ExpenseLine(
            categoryId = category.id,
            name = category.name,
            amount = MoneyFormat.roundMoney(amount),
        )
    }

    fun buildMonth(
        year: Int,
        month: Int,
        sources: List<PlannedIncomeSourceEntity>,
        expenseCategories: List<BudgetCategory>,
        categoryPlans: List<MonthlyCategoryPlanEntity>,
        incomePlanOverrides: List<MonthlyIncomePlanEntity>,
        obligations: List<PlannedObligationEntity> = emptyList(),
        fact: MonthFact?,
    ): MonthPlan {
        val overridesBySource = incomePlanOverrides.associateBy { it.sourceId }
        val plansByCategory = categoryPlans.associateBy { it.categoryId }
        val obligationsByCategory = PlannedObligationHelper.monthlyPlanByCategory(obligations)

        val incomeLines = sources.mapNotNull { incomeLineFor(it, overridesBySource, month) }
        val expenseLines = expenseCategories.mapNotNull {
            expenseLineFor(it, plansByCategory[it.id], obligationsByCategory[it.id] ?: 0.0)
        }

        val incomeTotal = MoneyFormat.roundMoney(incomeLines.sumOf { it.amount })
        val expenseTotal = MoneyFormat.roundMoney(expenseLines.sumOf { it.amount })
        return MonthPlan(
            year = year,
            month = month,
            incomeLines = incomeLines,
            expenseLines = expenseLines,
            incomeTotal = incomeTotal,
            expenseTotal = expenseTotal,
            net = MoneyFormat.roundMoney(incomeTotal - expenseTotal),
            fact = fact,
        )
    }

    fun buildYear(
        year: Int,
        sources: List<PlannedIncomeSourceEntity>,
        expenseCategories: List<BudgetCategory>,
        categoryPlansByMonth: Map<Int, List<MonthlyCategoryPlanEntity>>,
        incomeOverridesByMonth: Map<Int, List<MonthlyIncomePlanEntity>>,
        obligations: List<PlannedObligationEntity> = emptyList(),
        factByMonth: Map<Int, MonthFact>,
        factByCategory: Map<Int, Double> = emptyMap(),
    ): YearPlan {
        val months = monthKeys(year).map { (y, m) ->
            buildMonth(
                year = y,
                month = m,
                sources = sources,
                expenseCategories = expenseCategories,
                categoryPlans = categoryPlansByMonth[m] ?: emptyList(),
                incomePlanOverrides = incomeOverridesByMonth[m] ?: emptyList(),
                obligations = obligations,
                fact = factByMonth[m],
            )
        }
        val quarters = (1..4).map { q ->
            val quarterMonths = months.filter { (it.month - 1) / 3 + 1 == q }
            val income = MoneyFormat.roundMoney(quarterMonths.sumOf { it.incomeTotal })
            val expense = MoneyFormat.roundMoney(quarterMonths.sumOf { it.expenseTotal })
            QuarterPlan(
                quarter = q,
                incomeTotal = income,
                expenseTotal = expense,
                net = MoneyFormat.roundMoney(income - expense),
            )
        }
        val yearIncome = MoneyFormat.roundMoney(months.sumOf { it.incomeTotal })
        val yearExpense = MoneyFormat.roundMoney(months.sumOf { it.expenseTotal })
        return YearPlan(
            year = year,
            months = months,
            quarters = quarters,
            incomeTotal = yearIncome,
            expenseTotal = yearExpense,
            net = MoneyFormat.roundMoney(yearIncome - yearExpense),
            factByCategory = factByCategory,
        )
    }

    fun factTotals(transactions: List<TransactionEntity>): MonthFact {
        val (income, expense) = ForecastVsFactHelper.factTotals(transactions)
        return MonthFact(income, expense)
    }

    fun isClosedMonth(year: Int, month: Int, currentYear: Int, currentMonth: Int): Boolean {
        return year < currentYear || (year == currentYear && month < currentMonth)
    }

    fun expenseFixations(
        year: Int,
        month: Int,
        budgetId: Int,
        expenseCategories: List<BudgetCategory>,
        categoryPlans: List<MonthlyCategoryPlanEntity>,
        obligations: List<PlannedObligationEntity>,
    ): List<MonthlyCategoryPlanEntity> {
        val plansByCategory = categoryPlans.associateBy { it.categoryId }
        val obligationsByCategory = PlannedObligationHelper.monthlyPlanByCategory(obligations)
        return expenseCategories.map { category ->
            val amount = MoneyFormat.roundMoney(
                PlannedObligationHelper.effectivePlan(
                    MonthlyPlanHelper.effectivePlannedAmount(category, plansByCategory[category.id]),
                    obligationsByCategory[category.id] ?: 0.0,
                ),
            )
            MonthlyCategoryPlanEntity(
                year = year,
                month = month,
                categoryId = category.id,
                budgetId = budgetId,
                plannedAmount = amount,
                isEnabled = amount > 0.0,
            )
        }
    }

    fun isPastOrCurrentMonth(year: Int, month: Int, currentYear: Int, currentMonth: Int): Boolean {
        return year < currentYear || (year == currentYear && month <= currentMonth)
    }
}
