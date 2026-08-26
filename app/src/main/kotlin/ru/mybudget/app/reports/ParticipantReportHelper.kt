package ru.mybudget.app.reports

import ru.mybudget.app.data.TransactionEntity

object ParticipantReportHelper {
    data class Row(
        val name: String,
        val total: Double,
        val count: Int,
    )

    fun buildExpenseReport(
        transactions: List<TransactionEntity>,
        fromMs: Long,
        toMs: Long,
        configuredNames: List<String>,
        unlabeledName: String,
    ): List<Row> {
        val expenses = transactions.filter { tx ->
            tx.type == "expense" && tx.date >= fromMs && tx.date < toMs
        }
        val totals = linkedMapOf<String, Pair<Double, Int>>()
        configuredNames.forEach { totals[it] = 0.0 to 0 }

        for (transaction in expenses) {
            val key = transaction.participantLabel.trim().ifBlank { unlabeledName }
            val (sum, count) = totals.getOrDefault(key, 0.0 to 0)
            totals[key] = sum + transaction.amount to count + 1
        }

        return totals.entries
            .map { (name, value) -> Row(name, value.first, value.second) }
            .sortedWith(compareByDescending<Row> { it.total }.thenBy { it.name })
    }

    fun totalExpenses(rows: List<Row>): Double = rows.sumOf { it.total }
}
