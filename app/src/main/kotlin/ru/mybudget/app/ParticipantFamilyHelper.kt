package ru.mybudget.app

import ru.mybudget.app.data.TransactionEntity

object ParticipantFamilyHelper {
    data class FamilySummary(
        val participantName: String,
        val myExpenseTotal: Double,
        val allExpenseTotal: Double,
    )

    fun buildSummary(
        transactions: List<TransactionEntity>,
        monthStartMs: Long,
        participantName: String,
    ): FamilySummary? {
        if (participantName.isBlank()) return null
        val monthExpenses = transactions.filter { it.type == "expense" && it.date >= monthStartMs }
        val myTotal = monthExpenses.filter { it.participantLabel == participantName }.sumOf { it.amount }
        val allTotal = monthExpenses.sumOf { it.amount }
        if (myTotal <= 0.0 && allTotal <= 0.0) return null
        return FamilySummary(
            participantName = participantName,
            myExpenseTotal = myTotal,
            allExpenseTotal = allTotal,
        )
    }
}
