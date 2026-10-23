package ru.mybudget.app

import org.junit.Assert.*
import org.junit.Test
import ru.mybudget.app.data.TransactionEntity
import java.time.YearMonth

class IncomeMonthFactsTest {
    private val month=YearMonth.of(2026,10)
    private val range=MonthlyPlanHelper.monthRangeMs(2026,10)
    private fun receipt(id:Int=1,date:Long=range.first,source:Int?=null,planMonth:String?=null,type:String="income")=
        TransactionEntity(id=id,categoryId=1,amount=100.0,type=type,description="",date=date,plannedIncomeSourceId=source,plannedIncomeMonth=planMonth)
    @Test fun ordinaryIncomeVisibleWithoutSource() {
        assertEquals(1,IncomeMonthFacts.receipts(listOf(receipt()),month).size)
        assertEquals(1,IncomeMonthFacts.unlinked(listOf(receipt()),month).size)
    }
    @Test fun monthBoundsAreHalfOpenAndExpensesExcluded() {
        val rows=listOf(receipt(date=range.first-1),receipt(id=2,date=range.first),receipt(id=3,date=range.second-1),receipt(id=4,date=range.second),receipt(id=5,type="expense"))
        assertEquals(listOf(2,3),IncomeMonthFacts.receipts(rows,month).map { it.id })
    }
    @Test fun linkedReceiptUsesPlanMonthNotBookingDate() {
        val row=receipt(date=0,source=7,planMonth="2026-10")
        assertEquals(listOf(row),IncomeMonthFacts.receipts(listOf(row),month))
        assertTrue(IncomeMonthFacts.unlinked(listOf(row),month).isEmpty())
        assertTrue(IncomeMonthFacts.receipts(listOf(receipt(source=7,planMonth="2026-11")),month).isEmpty())
    }
    @Test fun malformedLegacyLinkUsesBookingDate() {
        assertEquals(1,IncomeMonthFacts.receipts(listOf(receipt(source=7,planMonth="invalid")),month).size)
    }
    @Test fun noPlanStillShowsActualIncome() {
        val result=PlanOverviewHelper.summarize(1,month,listOf(BudgetCategory(1,"Income",0)),emptyList(),emptyList(),emptyList(),listOf(receipt()))
        assertFalse(result.hasIncomePlan)
        assertEquals(100.0,result.incomeReceived,0.0)
        assertEquals(100.0,result.incomeUnlinked,0.0)
    }
}
