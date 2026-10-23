package ru.mybudget.app
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import ru.mybudget.app.data.*
@RunWith(AndroidJUnit4::class)
class BalanceCoverageIntegrityTest {
 private lateinit var db:BudgetDatabase
 private val dao get()=db.budgetDao()
 @Before fun setup()=runBlocking {
  db=Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(),BudgetDatabase::class.java).addCallback(BalanceLedgerSchema.callback).build()
  dao.insertBudgetProfile(BudgetProfileEntity(id=1,name="Test"))
  dao.insertCategory(BudgetCategoryEntity(1,"Debt",0,currentBalance=-100.0))
  dao.insertCategory(BudgetCategoryEntity(2,"A",0,currentBalance=60.0))
  dao.insertCategory(BudgetCategoryEntity(3,"B",0,currentBalance=80.0))
 }
 @After fun close()=db.close()
 private val expected=mapOf(1 to -100.0,2 to 60.0,3 to 80.0)
 @Test fun transferPreservesTotalAndDoesNotCreateIncomeOrExpense()=runBlocking {
  BalanceCoverage.execute(db,1,1,mapOf(2 to 60.0,3 to 40.0),expected,"once")
  BalanceCoverage.execute(db,1,1,mapOf(2 to 60.0,3 to 40.0),expected,"once")
  assertEquals(0.0,dao.getCategoryById(1)!!.currentBalance,0.0)
  assertEquals(40.0,dao.getActiveBudgetProfileBalances().sumOf { it.total },0.0)
  assertEquals(40.0,dao.getAllBalanceMovements().sumOf { it.budgetDelta },0.0)
  assertTrue(dao.getAllTransactionsOnce().isEmpty())
 }
 @Test fun staleBalanceRejectsWholeCommand()=runBlocking {
  dao.applyBalanceDelta(3,-5.0)
  val before=dao.getAllBalanceMovements()
  assertTrue(runCatching { BalanceCoverage.execute(db,1,1,mapOf(2 to 60.0,3 to 40.0),expected,"stale") }.isFailure)
  assertEquals(60.0,dao.getCategoryById(2)!!.currentBalance,0.0)
  assertEquals(-100.0,dao.getCategoryById(1)!!.currentBalance,0.0)
  assertEquals(before,dao.getAllBalanceMovements())
 }
 @Test fun deletedBudgetCannotReceiveTransfers()=runBlocking {
  dao.deactivateBudgetProfile(1)
  assertTrue(runCatching { BalanceCoverage.execute(db,1,1,mapOf(2 to 40.0),expected,"deleted") }.isFailure)
  assertEquals(-100.0,dao.getCategoryById(1)!!.currentBalance,0.0)
 }
}
