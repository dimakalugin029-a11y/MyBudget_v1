package ru.mybudget.app

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.mybudget.app.data.TransactionEntity
import java.util.Calendar

class YearOverviewHelperTest {
    @Test
    fun build_aggregatesYearTotals() {
        val jan = monthStartMs(2026, 1)
        val feb = monthStartMs(2026, 2)
        val transactions = listOf(
            TransactionEntity(categoryId = 1, amount = 50_000.0, type = "income", description = "", date = jan + 1),
            TransactionEntity(categoryId = 2, amount = 10_000.0, type = "expense", description = "", date = jan + 2),
            TransactionEntity(categoryId = 2, amount = 5_000.0, type = "expense", description = "", date = feb + 2),
        )
        val categories = listOf(
            BudgetCategory(id = 1, name = "Доходы", parentId = 0),
            BudgetCategory(id = 2, name = "Продукты", parentId = 0),
        )

        val overview = YearOverviewHelper.build(2026, transactions, categories)

        assertEquals(50_000.0, overview.totalIncome, 0.01)
        assertEquals(15_000.0, overview.totalExpense, 0.01)
        assertEquals(35_000.0, overview.totalSaldo, 0.01)
        assertEquals(3, overview.transactionCount)
        assertEquals(2026, overview.year)
    }

    @Test
    fun availableYears_includesCurrentYearWhenEmpty() {
        val years = YearOverviewHelper.availableYears(emptyList(), todayYear = 2026)
        assertEquals(listOf(2026), years)
    }

    private fun monthStartMs(year: Int, month: Int): Long {
        return Calendar.getInstance().apply {
            set(Calendar.YEAR, year)
            set(Calendar.MONTH, month - 1)
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }
}
