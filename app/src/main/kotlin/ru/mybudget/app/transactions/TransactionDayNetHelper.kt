package ru.mybudget.app.transactions

import ru.mybudget.app.data.TransactionEntity

object TransactionDayNetHelper {
    fun isInterAccountGroup(members: List<TransactionEntity>, remainderLabel: String): Boolean {
        if (members.isEmpty()) return false
        val hasIncome = members.any { it.type == "income" }
        val hasExpense = members.any { it.type != "income" }
        if (hasIncome && hasExpense) return true
        return members.first().description == remainderLabel
    }

    fun groupNet(members: List<TransactionEntity>): Double {
        return members.sumOf { singleNet(it) }
    }

    fun singleNet(transaction: TransactionEntity): Double {
        return if (transaction.type == "income") transaction.amount else -transaction.amount
    }

    fun computeDailyNets(
        transactions: List<TransactionEntity>,
        remainderLabel: String,
        dayKeyFor: (Long) -> String,
    ): Map<String, Double> {
        if (transactions.isEmpty()) return emptyMap()
        val grouped = transactions
            .filter { !it.groupId.isNullOrBlank() }
            .groupBy { it.groupId.orEmpty() }
        val consumed = mutableSetOf<String>()
        val totals = linkedMapOf<String, Double>()
        for (tx in transactions) {
            val groupId = tx.groupId
            if (groupId.isNullOrBlank()) {
                val key = dayKeyFor(tx.date)
                totals[key] = (totals[key] ?: 0.0) + singleNet(tx)
            } else if (consumed.add(groupId)) {
                val members = grouped[groupId].orEmpty()
                if (members.isNotEmpty() && !isInterAccountGroup(members, remainderLabel)) {
                    val latest = members.maxOf { it.date }
                    val key = dayKeyFor(latest)
                    totals[key] = (totals[key] ?: 0.0) + groupNet(members)
                }
            }
        }
        return totals
    }
}
