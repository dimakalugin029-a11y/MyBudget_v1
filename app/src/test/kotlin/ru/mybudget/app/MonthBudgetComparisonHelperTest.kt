package ru.mybudget.app

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.mybudget.app.data.TransactionEntity

class MonthBudgetComparisonHelperTest {
    @Test
    fun compare_calculatesDeltasBetweenMonths() {
        val (fromA, toA) = MonthBudgetComparisonHelper.monthRangeMs(2026, 3)
        val (fromB, toB) = MonthBudgetComparisonHelper.monthRangeMs(2026, 2)
        val categories = listOf(
            BudgetCategory(id = 1, name = "Еда", parentId = 0),
            BudgetCategory(id = 2, name = "Хлеб", parentId = 1),
        )
        val txs = listOf(
            TransactionEntity(categoryId = 2, amount = 1000.0, type = "expense", description = "", date = fromA + 1),
            TransactionEntity(categoryId = 2, amount = 500.0, type = "expense", description = "", date = fromB + 1),
            TransactionEntity(categoryId = 1, amount = 30000.0, type = "income", description = "", date = fromA + 2),
        )
        val snapshotA = MonthBudgetComparisonHelper.buildSnapshot(2026, 3, txs, categories, fromA, toA)
        val snapshotB = MonthBudgetComparisonHelper.buildSnapshot(2026, 2, txs, categories, fromB, toB)
        val comparison = MonthBudgetComparisonHelper.compare(snapshotA, snapshotB)

        assertEquals(30000.0, comparison.monthA.totals.income, 0.01)
        assertEquals(1000.0, comparison.monthA.totals.expense, 0.01)
        assertEquals(500.0, comparison.expenseDelta, 0.01)
    }
}
