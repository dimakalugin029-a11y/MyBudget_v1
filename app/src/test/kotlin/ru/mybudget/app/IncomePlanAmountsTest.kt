package ru.mybudget.app

import org.junit.Assert.*
import org.junit.Test
import ru.mybudget.app.data.*
import java.time.YearMonth

class IncomePlanAmountsTest {
    private val month = YearMonth.of(2026, 10)
    private val salary = PlannedIncomeSourceEntity(id = 7, budgetId = 1, name = "Зарплата",
        amount = 1000.0, sourceType = "salary", dayOfMonth = 21)

    @Test fun quarterlyPaymentMatchesCalendarMonths() {
        val bonus = salary.copy(sourceType = "bonus", periodType = "quarterly", dueMonth = 3)
        for (m in 1..12) assertEquals(if (m in setOf(3, 6, 9, 12)) 1000.0 else 0.0,
            IncomePlanAmounts.planned(bonus, m), 0.001)
        assertEquals(333.33, PlannedIncomeHelper.monthlyEquivalent(bonus), 0.001)
    }

    @Test fun annualPaymentIsNotSpreadAcrossMonths() {
        val bonus = salary.copy(amount = 120000.0, sourceType = "bonus", periodType = "yearly", dueMonth = 12)
        assertEquals(0.0, IncomePlanAmounts.planned(bonus, 10), 0.001)
        assertEquals(120000.0, IncomePlanAmounts.planned(bonus, 12), 0.001)
        assertEquals(10000.0, PlannedIncomeHelper.monthlyEquivalent(bonus), 0.001)
    }

    @Test fun overridesCanAddOrDisableAMonth() {
        val annual = salary.copy(sourceType = "bonus", periodType = "yearly", dueMonth = 12)
        val override = MonthlyIncomePlanEntity(2026, 10, 7, 1, 500.0)
        assertEquals(500.0, IncomePlanAmounts.planned(annual, 10, override), 0.001)
        assertEquals(0.0, IncomePlanAmounts.planned(annual, 10, override.copy(isEnabled = false)), 0.001)
        assertEquals(0.0, IncomePlanAmounts.planned(annual.copy(isActive = false), 10, override), 0.001)
    }

    @Test fun onlyLinkedIncomeForSelectedPeriodCounts() {
        val receipt = TransactionEntity(categoryId = 1, amount = 200.0, type = "income", description = "",
            plannedIncomeSourceId = 7, plannedIncomeMonth = month.toString())
        val transactions = listOf(receipt, receipt.copy(amount = 300.0), receipt.copy(type = "expense"),
            receipt.copy(plannedIncomeMonth = "2026-09"), receipt.copy(plannedIncomeSourceId = null))
        val progress = IncomePlanAmounts.progress(salary, month, emptyList(), transactions)
        assertEquals(500.0, progress.received, 0.001)
        assertEquals(500.0, progress.remaining, 0.001)
        assertEquals(0.0, IncomePlanAmounts.progress(salary.copy(amount = 100.0), month, emptyList(), transactions).remaining, 0.001)
    }
}
