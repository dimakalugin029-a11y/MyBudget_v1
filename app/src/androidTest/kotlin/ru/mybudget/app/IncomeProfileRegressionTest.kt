package ru.mybudget.app

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import ru.mybudget.app.data.*
import java.time.YearMonth

@RunWith(AndroidJUnit4::class)
class IncomeProfileRegressionTest {
    private lateinit var db:BudgetDatabase
    private val dao get()=db.budgetDao()
    private val month=YearMonth.of(2026,10)
    private val date=MonthlyPlanHelper.monthRangeMs(2026,10).first+1000
    @Before fun setup()=runBlocking {
        db=Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(),BudgetDatabase::class.java)
            .addCallback(BalanceLedgerSchema.callback).build()
        dao.insertBudgetProfile(BudgetProfileEntity(id=1,name="Main"))
        dao.insertBudgetProfile(BudgetProfileEntity(id=2,name="Test"))
        dao.insertBudgetProfile(BudgetProfileEntity(id=3,name="Empty"))
        dao.insertCategory(BudgetCategoryEntity(1,"Parent",0,currentBalance=1000.0))
        dao.insertCategory(BudgetCategoryEntity(2,"Child",1,currentBalance=1000.0))
        dao.insertCategory(BudgetCategoryEntity(3,"Test",0,budgetId=2,currentBalance=300.0))
        dao.insertPlannedIncomeSource(PlannedIncomeSourceEntity(id=7,budgetId=1,name="Advance",amount=100.0,sourceType="advance",dayOfMonth=15))
    }
    @After fun close()=db.close()
    @Test fun deletedBudgetExcludedImmediatelyAndEmptyProfileStillPresent()=runBlocking {
        assertEquals(1300.0,dao.getActiveBudgetProfileBalances().sumOf { it.total },0.0)
        dao.deactivateBudgetProfile(2)
        val totals=dao.getActiveBudgetProfileBalances()
        assertEquals(setOf(1,3),totals.map { it.id }.toSet())
        assertEquals(1000.0,totals.sumOf { it.total },0.0)
        assertEquals(300.0,dao.getCategoryByIdAny(3)!!.currentBalance,0.0)
        assertEquals(0.0,totals.single { it.id==3 }.total,0.0)
    }
    @Test fun profileTotalsUseFreshRootAmountsAndExcludeArchivedCategories()=runBlocking {
        dao.applyBalanceDelta(2,-100.0)
        assertEquals(1200.0,dao.getActiveBudgetProfileBalances().sumOf { it.total },0.0)
        dao.updateCategory(dao.getCategoryByIdAny(3)!!.copy(isActive=false))
        assertEquals(900.0,dao.getActiveBudgetProfileBalances().sumOf { it.total },0.0)
    }
    @Test fun linkingAdvanceDoesNotCreateMoneyOrLedgerRows()=runBlocking {
        dao.recordTransaction(2,145.0,"income","Advance",date=date)
        val ledger=dao.getAllBalanceMovements()
        val linker=IncomeReceiptLinker(db)
        val candidate=linker.candidates(1,month).single()
        linker.link(7,month,candidate.rows)
        assertEquals(1,dao.getAllTransactionsOnce().size)
        assertEquals(ledger,dao.getAllBalanceMovements())
        assertEquals(1145.0,dao.getCategoryById(1)!!.currentBalance,0.0)
        assertEquals(145.0,dao.getIncomePlanTransactions(1).sumOf { it.amount },0.0)
        assertTrue(linker.candidates(1,month).isEmpty())
        assertTrue(runCatching { linker.link(7,month,candidate.rows) }.isFailure)
        assertEquals(1145.0,dao.getCategoryById(2)!!.currentBalance,0.0)
    }
    @Test fun distributedIncomeLinkedAsWholeWithoutDoubleCount()=runBlocking {
        dao.recordTransaction(1,50.0,"income","Part A",groupId="distribution",date=date)
        dao.recordTransaction(2,75.0,"income","Part B",groupId="distribution",date=date)
        val linker=IncomeReceiptLinker(db)
        val candidate=linker.candidates(1,month).single()
        assertEquals(2,candidate.rows.size)
        assertEquals(125.0,candidate.amount,0.0)
        assertTrue(runCatching { linker.link(7,month,candidate.rows.take(1)) }.isFailure)
        assertTrue(dao.getIncomePlanTransactions(1).isEmpty())
        linker.link(7,month,candidate.rows)
        assertEquals(125.0,dao.getIncomePlanTransactions(1).sumOf { it.amount },0.0)
    }
    @Test fun foreignBudgetWrongMonthAndStaleConfirmationRejected()=runBlocking {
        dao.recordTransaction(3,20.0,"income","Other",date=date)
        dao.recordTransaction(2,30.0,"income","Advance",date=date)
        val linker=IncomeReceiptLinker(db)
        val own=linker.candidates(1,month).single()
        val other=linker.candidates(2,month).single()
        assertTrue(runCatching { linker.link(7,month,other.rows) }.isFailure)
        assertTrue(runCatching { linker.link(7,month.plusMonths(1),own.rows) }.isFailure)
        dao.correctTransaction(own.rows.single().id,2,35.0,"income","Corrected","")
        assertTrue(runCatching { linker.link(7,month,own.rows) }.isFailure)
        assertTrue(dao.getIncomePlanTransactions(1).isEmpty())
    }
    @Test fun cancellingLinkedExistingReceiptReversesOnlyOriginalIncome()=runBlocking {
        dao.recordTransaction(2,45.0,"income","Advance",date=date)
        val linker=IncomeReceiptLinker(db)
        linker.link(7,month,linker.candidates(1,month).single().rows)
        dao.revertAndDeleteTransaction(dao.getIncomePlanTransactions(1).single())
        assertEquals(1000.0,dao.getCategoryById(1)!!.currentBalance,0.0)
        assertTrue(dao.getIncomePlanTransactions(1).isEmpty())
        assertEquals(0.0,IncomePlanAmounts.progress(dao.getIncomeSourceById(7)!!,month,emptyList(),dao.getIncomePlanTransactions(1)).received,0.0)
    }
    @Test fun mixedExpenseGroupCannotBeMistakenForReceipt()=runBlocking {
        dao.recordTransaction(2,50.0,"income","Income",groupId="mixed",date=date)
        dao.recordTransaction(2,10.0,"expense","Expense",groupId="mixed",date=date)
        assertTrue(IncomeReceiptLinker(db).candidates(1,month).isEmpty())
    }
}
