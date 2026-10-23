package ru.mybudget.app
import org.junit.Assert.*
import org.junit.Test
import ru.mybudget.app.data.*
import java.time.*
class IncomeCompletionTest {
 private val month=YearMonth.of(2026,10)
 private val source=PlannedIncomeSourceEntity(id=7,budgetId=1,name="Advance",amount=14500.0,sourceType="advance",dayOfMonth=15)
 private fun receipt(final:Boolean)=TransactionEntity(id=1,categoryId=1,amount=14100.0,type="income",description="",date=month.atDay(15).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(),plannedIncomeSourceId=7,plannedIncomeMonth=month.toString(),incomePlanFinal=final)
 @Test fun finalReceiptKeepsEstimateAndDifferenceButClosesExpectation() {
  val p=IncomePlanAmounts.progress(source,month,emptyList(),listOf(receipt(true)))
  assertEquals(14500.0,p.planned,0.0);assertEquals(14100.0,p.received,0.0);assertEquals(-400.0,p.deviation,0.0);assertEquals(0.0,p.remaining,0.0);assertTrue(p.finalized)
 }
 @Test fun partialReceiptKeepsRemainingExpected() { assertEquals(400.0,IncomePlanAmounts.progress(source,month,emptyList(),listOf(receipt(false))).remaining,0.0) }
 @Test fun otherMonthAndExpenseCannotCloseSource() {
  for(row in listOf(receipt(true).copy(type="expense"),receipt(true).copy(plannedIncomeMonth="2026-09"),receipt(true).copy(plannedIncomeSourceId=8))) assertFalse(IncomePlanAmounts.progress(source,month,emptyList(),listOf(row)).finalized)
 }
 private fun forecast(final:Boolean,sources:List<PlannedIncomeSourceEntity> = listOf(source))=ForecastHelper.build(ForecastHelper.Inputs(startBalance=14100.0,today=month.atDay(23),horizon=ForecastHelper.Horizon.QUARTER,scenario=ForecastHelper.Scenario.OPTIMISTIC,incomeSources=sources,obligations=emptyList(),monthlyPlans=emptyList(),pastTransactions=listOf(receipt(final)),goals=emptyList(),goalBalances=emptyMap(),includeCurrentMonth=true))
 @Test fun finalDifferenceIsNotForecastMoneyAndNextMonthRemainsPlanned() {
  val result=forecast(true)
  assertEquals(0.0,result.months.first().totalIncome,0.0)
  assertEquals(14100.0,result.months.first().projectedEndBalance,0.0)
  assertEquals(14500.0,result.months[1].totalIncome,0.0)
 }
 @Test fun partialDifferenceIsForecastMoney() { assertEquals(400.0,forecast(false).months.first().totalIncome,0.0) }
 @Test fun cancellingAllReceiptsReopensExpectation() { assertEquals(14500.0,IncomePlanAmounts.progress(source,month,emptyList(),emptyList()).remaining,0.0) }
 @Test fun closingAdvanceDoesNotCloseOtherIncomeSource() {
  assertEquals(5000.0,forecast(true,listOf(source,source.copy(id=8,name="Salary",amount=5000.0))).months.first().totalIncome,0.0)
 }

}
