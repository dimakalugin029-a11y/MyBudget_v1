package ru.mybudget.app

/** Suggestions only: posting is performed by the existing income distribution command. */
object OverspendCoverage {
    fun candidates(categories: List<BudgetCategory>, budgetId: Int): List<BudgetCategory> {
        val withChildren = categories.filter { it.isActive && it.budgetId == budgetId }.map { it.parentId }.toSet()
        return categories.filter {
            it.budgetId == budgetId && it.isActive && it.id !in withChildren &&
                it.currentBalance.isFinite() && MoneyFormat.roundMoney(it.currentBalance) < 0.0
        }
    }

    fun deficit(category: BudgetCategory): Double = MoneyFormat.roundMoney(-category.currentBalance).coerceAtLeast(0.0)

    fun valid(amount: Double?, maximum: Double): Boolean = amount != null && amount.isFinite() &&
        amount >= 0.0 && MoneyFormat.roundMoney(amount) <= maximum

    fun fits(income: Double, other: Double, coverage: Double): Boolean =
        income.isFinite() && other.isFinite() && coverage.isFinite() && income > 0.0 &&
            other >= 0.0 && coverage >= 0.0 &&
            MoneyFormat.roundMoney(other + coverage) <= MoneyFormat.roundMoney(income)
}
