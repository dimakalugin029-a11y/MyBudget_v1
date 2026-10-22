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

@RunWith(AndroidJUnit4::class)
class SingleMoneyCommandTest {
    private lateinit var db:BudgetDatabase
    private val dao get()=db.budgetDao()
    @Before fun setup()=runBlocking {
        db=Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(),BudgetDatabase::class.java)
            .addCallback(BalanceLedgerSchema.callback).build()
        dao.insertBudgetProfile(BudgetProfileEntity(id=1,name="Test"))
        dao.insertCategory(BudgetCategoryEntity(1,"Parent",0,budgetId=1,currentBalance=100.0))
        dao.insertCategory(BudgetCategoryEntity(2,"Child",1,budgetId=1,currentBalance=100.0))
    }
    @After fun close()=db.close()
    private fun command(id:String="receipt",budget:Int=1,type:String="expense",amount:Double=25.0)=
        SingleMoneyCommand(id,budget,2,amount,type,"Test")
    @Test fun duplicateAndCancelledReceiptCannotChangeMoneyAgain()=runBlocking {
        val executor=SingleMoneyCommandExecutor(db)
        assertEquals(SingleMoneyCommandExecutor.Outcome.SAVED,executor.execute(command()).outcome)
        assertEquals(SingleMoneyCommandExecutor.Outcome.ALREADY_PROCESSED,executor.execute(command()).outcome)
        assertEquals(75.0,dao.getCategoryById(1)!!.currentBalance,0.0)
        assertEquals(1,dao.getAllTransactionsOnce().size)
        dao.cancelTransactionGroup("receipt")
        assertEquals(SingleMoneyCommandExecutor.Outcome.ALREADY_PROCESSED,executor.execute(command()).outcome)
        assertTrue(dao.getAllTransactionsOnce().isEmpty())
        assertEquals(100.0,dao.getAllBalanceMovements().sumOf { it.budgetDelta },0.0)
    }
    @Test fun staleBudgetAndInvalidAmountsLeaveNoWrites()=runBlocking {
        val executor=SingleMoneyCommandExecutor(db)
        for(c in listOf(command(budget=2),command(amount=Double.NaN),command(amount=0.0))) {
            assertTrue(runCatching { executor.execute(c) }.isFailure)
        }
        assertTrue(dao.getAllTransactionsOnce().isEmpty())
        assertEquals(100.0,dao.getCategoryById(2)!!.currentBalance,0.0)
        assertEquals(SingleMoneyCommandExecutor.Outcome.SAVED,executor.execute(command()).outcome)
    }
    @Test fun incomeAndExpenseAgreeWithLedger()=runBlocking {
        val executor=SingleMoneyCommandExecutor(db)
        executor.execute(command("income",type="income",amount=50.0))
        executor.execute(command("expense"))
        assertEquals(125.0,dao.getCategoryById(1)!!.currentBalance,0.0)
        assertEquals(125.0,dao.getAllBalanceMovements().sumOf { it.budgetDelta },0.0)
    }
}
