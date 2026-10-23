package ru.mybudget.app
import org.junit.Assert.*
import org.junit.Test
class BalanceCoverageTest {
 private val cats=listOf(BudgetCategory(1,"Debt",0,currentBalance=-100.0),BudgetCategory(2,"A",0,currentBalance=60.0),BudgetCategory(3,"B",0,currentBalance=80.0))
 @Test fun multipleSourcesCoverSingleDebt() { assertEquals(100.0,BalanceCoverage.plan(cats,1,1,mapOf(2 to 60.0,3 to 40.0)).sumOf { it.amount },0.0) }
 @Test fun partialCoverageAllowed() { assertEquals(30.0,BalanceCoverage.plan(cats,1,1,mapOf(2 to 30.0)).single().amount,0.0) }
 @Test fun invalidAmountsCannotCreateDebtOrOverfill() {
  for(m in listOf(mapOf(2 to 61.0),mapOf(2 to -1.0),mapOf(2 to Double.NaN),mapOf(2 to 60.0,3 to 41.0),emptyMap())) assertTrue(runCatching { BalanceCoverage.plan(cats,1,1,m) }.isFailure)
 }
 @Test fun foreignArchivedAndParentSourcesRejected() {
  for(extra in listOf(cats[1].copy(budgetId=2),cats[1].copy(isActive=false))) assertTrue(runCatching { BalanceCoverage.plan(listOf(cats[0],extra),1,1,mapOf(2 to 10.0)) }.isFailure)
  assertTrue(runCatching { BalanceCoverage.plan(cats+BudgetCategory(4,"Child",2,currentBalance=60.0),1,1,mapOf(2 to 10.0)) }.isFailure)
 }
}
