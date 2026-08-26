package ru.mybudget.app.reports

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mybudget.app.data.TransactionEntity

class ParticipantReportHelperTest {
    @Test
    fun buildExpenseReport_groupsByParticipantAndSumsAmounts() {
        val now = System.currentTimeMillis()
        val transactions = listOf(
            TransactionEntity(
                categoryId = 1,
                amount = 100.0,
                type = "expense",
                description = "a",
                date = now,
                participantLabel = "Мама",
            ),
            TransactionEntity(
                categoryId = 1,
                amount = 250.0,
                type = "expense",
                description = "b",
                date = now,
                participantLabel = "Папа",
            ),
            TransactionEntity(
                categoryId = 1,
                amount = 50.0,
                type = "expense",
                description = "c",
                date = now,
                participantLabel = "",
            ),
            TransactionEntity(
                categoryId = 1,
                amount = 999.0,
                type = "income",
                description = "salary",
                date = now,
                participantLabel = "Папа",
            ),
        )
        val rows = ParticipantReportHelper.buildExpenseReport(
            transactions = transactions,
            fromMs = now - 1_000,
            toMs = now + 1_000,
            configuredNames = listOf("Мама", "Папа"),
            unlabeledName = "Без метки",
        )
        val mama = rows.first { it.name == "Мама" }
        val papa = rows.first { it.name == "Папа" }
        val unlabeled = rows.first { it.name == "Без метки" }
        assertEquals(100.0, mama.total, 0.01)
        assertEquals(250.0, papa.total, 0.01)
        assertEquals(50.0, unlabeled.total, 0.01)
        assertEquals(400.0, ParticipantReportHelper.totalExpenses(rows), 0.01)
        assertTrue(rows.indexOf(papa) < rows.indexOf(mama))
    }
}
