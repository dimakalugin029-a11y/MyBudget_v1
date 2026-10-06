package ru.mybudget.app

import ru.mybudget.app.data.TransactionEntity

object ForecastVsFactHelper {

    data class Delta(
        val absolute: Double,
        val percent: Double?,
    )

    data class MonthComparison(
        val year: Int,
        val month: Int,
        val forecastIncome: Double,
        val factIncome: Double,
        val forecastExpense: Double,
        val factExpense: Double,
        val forecastNet: Double,
        val factNet: Double,
        val incomeDelta: Delta,
        val expenseDelta: Delta,
        val netDelta: Delta,
    )

    data class LineComparison(
        val name: String,
        val forecastAmount: Double,
        val factAmount: Double,
        val delta: Delta,
    )

    data class MonthDetail(
        val incomeLines: List<LineComparison>,
        val expenseLines: List<LineComparison>,
    )

    fun factTotals(transactions: List<TransactionEntity>): Pair<Double, Double> {
        val income = transactions
            .filter { it.type == "income" && it.amount > 0.0 }
            .sumOf { it.amount }
        val expense = transactions
            .filter { it.type == "expense" && it.amount > 0.0 }
            .sumOf { it.amount }
        return MoneyFormat.roundMoney(income) to MoneyFormat.roundMoney(expense)
    }

    fun delta(forecast: Double, fact: Double): Delta {
        val absolute = MoneyFormat.roundMoney(fact - forecast)
        val percent = if (forecast != 0.0) {
            MoneyFormat.roundMoney((fact - forecast) / kotlin.math.abs(forecast) * 100.0)
        } else {
            null
        }
        return Delta(absolute, percent)
    }

    fun compareMonth(
        year: Int,
        month: Int,
        forecastIncome: Double,
        forecastExpense: Double,
        transactions: List<TransactionEntity>,
    ): MonthComparison {
        val (factIncome, factExpense) = factTotals(transactions)
        val forecastNet = MoneyFormat.roundMoney(forecastIncome - forecastExpense)
        val factNet = MoneyFormat.roundMoney(factIncome - factExpense)
        return MonthComparison(
            year = year,
            month = month,
            forecastIncome = forecastIncome,
            factIncome = factIncome,
            forecastExpense = forecastExpense,
            factExpense = factExpense,
            forecastNet = forecastNet,
            factNet = factNet,
            incomeDelta = delta(forecastIncome, factIncome),
            expenseDelta = delta(forecastExpense, factExpense),
            netDelta = delta(forecastNet, factNet),
        )
    }

    fun compareLines(
        forecastLines: List<Pair<String, Double>>,
        factByCategory: Map<String, Double>,
    ): List<LineComparison> {
        val result = forecastLines.map { (name, amount) ->
            val fact = MoneyFormat.roundMoney(factByCategory[name] ?: 0.0)
            LineComparison(name, MoneyFormat.roundMoney(amount), fact, delta(amount, fact))
        }
        val known = forecastLines.map { it.first }.toSet()
        val extra = factByCategory
            .filterKeys { it !in known }
            .filterValues { it > 0.0 }
            .map { (name, amount) ->
                val rounded = MoneyFormat.roundMoney(amount)
                LineComparison(name, 0.0, rounded, delta(0.0, rounded))
            }
        return result + extra
    }
}
