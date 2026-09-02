package ru.mybudget.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mybudget.app.data.TransactionEntity
import ru.mybudget.app.setup.SetupChecklistHelper

class SetupChecklistHelperTest {
    @Test
    fun buildProgress_showsUntilAllStepsDone() {
        val progress = SetupChecklistHelper.buildProgress(
            transactions = emptyList(),
            autoBackupEnabled = false,
            hasIncomePlan = false,
            checklistDismissed = false,
        )
        assertTrue(progress.shouldShow)
        assertEquals(0, progress.completedCount)
    }

    @Test
    fun buildProgress_hidesWhenDismissed() {
        val progress = SetupChecklistHelper.buildProgress(
            transactions = emptyList(),
            autoBackupEnabled = false,
            hasIncomePlan = false,
            checklistDismissed = true,
        )
        assertFalse(progress.shouldShow)
    }

    @Test
    fun buildProgress_marksExpenseAndIncomeDone() {
        val txs = listOf(
            TransactionEntity(categoryId = 1, amount = 100.0, type = "expense", description = ""),
            TransactionEntity(categoryId = 1, amount = 500.0, type = "income", description = ""),
        )
        val progress = SetupChecklistHelper.buildProgress(
            transactions = txs,
            autoBackupEnabled = true,
            hasIncomePlan = true,
            checklistDismissed = false,
        )
        assertFalse(progress.shouldShow)
        assertEquals(4, progress.completedCount)
    }
}
