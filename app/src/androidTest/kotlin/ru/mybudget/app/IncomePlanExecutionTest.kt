package ru.mybudget.app

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import ru.mybudget.app.data.*
import java.time.YearMonth

@RunWith(AndroidJUnit4::class)
class IncomePlanExecutionTest {
    private lateinit var db: BudgetDatabase
    private val month = YearMonth.of(2026, 10)
    @Before fun prepare() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), BudgetDatabase::class.java).addCallback(ru.mybudget.app.data.BalanceLedgerSchema.callback).build()
        val dao = db.budgetDao()
        dao.insertBudgetProfile(BudgetProfileEntity(id = 1, name = "Основной"))
        dao.insertCategory(BudgetCategoryEntity(1, "Продукты", 0))
        dao.insertCategory(BudgetCategoryEntity(2, "Авто", 0))
        dao.insertPlannedIncomeSource(PlannedIncomeSourceEntity(id = 7, budgetId = 1,
            name = "Зарплата", amount = 1000.0, sourceType = "salary", dayOfMonth = 21))
        Unit
    }
    @After fun close() { db.close() }

    @Test fun cancellationOfOneFinalDistributionLineReopensWholeReceipt()=runBlocking {
        val service=IncomePlanExecution(db)
        assertTrue(service.receive(7,month,listOf(1 to 400.0,2 to 500.0),"Final","final",finalReceipt=true))
        val dao=db.budgetDao()
        assertTrue(IncomePlanAmounts.progress(dao.getIncomeSourceById(7)!!,month,emptyList(),dao.getIncomePlanTransactions(1)).finalized)
        dao.revertAndDeleteTransaction(dao.getIncomePlanTransactions(1).single { it.categoryId==1 })
        val progress=IncomePlanAmounts.progress(dao.getIncomeSourceById(7)!!,month,emptyList(),dao.getIncomePlanTransactions(1))
        assertFalse(progress.finalized);assertEquals(500.0,progress.remaining,0.0)
    }
    @Test fun completionAndReopeningExistingIncomeDoNotChangeMoney()=runBlocking {
        val service=IncomePlanExecution(db);val dao=db.budgetDao()
        service.receive(7,month,listOf(1 to 900.0),"Partial","partial")
        val ledger=dao.getAllBalanceMovements()
        service.setCompletion(7,month,true)
        assertTrue(dao.getIncomePlanTransactions(1).single().incomePlanFinal)
        assertEquals(900.0,dao.getCategoryById(1)!!.currentBalance,0.0)
        service.setCompletion(7,month,false)
        assertFalse(dao.getIncomePlanTransactions(1).single().incomePlanFinal)
        assertEquals(ledger,dao.getAllBalanceMovements())
    }
    @Test fun ordinaryCorrectionPreservesFinalButChangingTypeReopensGroup()=runBlocking {
        val service=IncomePlanExecution(db);val dao=db.budgetDao()
        service.receive(7,month,listOf(1 to 500.0,2 to 400.0),"Final","final-correction",finalReceipt=true)
        val row=dao.getIncomePlanTransactions(1).first()
        dao.correctTransaction(row.id,1,450.0,"income","Changed","")
        assertTrue(dao.getTransactionById(row.id)!!.incomePlanFinal)
        dao.correctTransaction(row.id,1,50.0,"expense","Wrong type","")
        assertFalse(dao.getTransactionById(row.id)!!.incomePlanFinal)
        assertTrue(dao.getIncomePlanTransactions(1).none { it.incomePlanFinal })
    }

    @Test fun partialAndDistributedReceiptsShareOneRemainingAmount() = runBlocking {
        val service = IncomePlanExecution(db)
        assertTrue(service.receive(7, month, listOf(1 to 400.0), "Часть", "a"))
        assertTrue(service.receive(7, month, listOf(1 to 200.0, 2 to 400.0), "Остаток", "b"))
        assertFalse(service.receive(7, month, listOf(1 to 400.0), "Повтор", "a"))
        assertEquals(1000.0, db.budgetDao().getIncomePlanTransactions(1).sumOf { it.amount }, 0.001)
        assertEquals(600.0, db.budgetDao().getCategoryById(1)!!.currentBalance, 0.001)
        assertEquals(400.0, db.budgetDao().getCategoryById(2)!!.currentBalance, 0.001)
    }

    @Test fun cancelledReceiptCannotBeReplayedWithOldRequest() = runBlocking {
        val service=IncomePlanExecution(db)
        assertTrue(service.receive(7,month,listOf(1 to 400.0),"","cancelled"))
        db.budgetDao().cancelTransactionGroup("cancelled")
        assertFalse(service.receive(7,month,listOf(1 to 400.0),"","cancelled"))
        assertEquals(0.0,db.budgetDao().getCategoryById(1)!!.currentBalance,0.0)
        assertTrue(service.receive(7,month,listOf(1 to 400.0),"","new-receipt"))
    }

    @Test fun concurrentSameRequestDoesNotDoubleCredit() = runBlocking {
        val results = coroutineScope { (1..2).map { n -> async(Dispatchers.IO) {
            runCatching { IncomePlanExecution(db).receive(7, month, listOf(1 to 1000.0), "", "same-request") }.getOrDefault(false)
        } }.awaitAll() }
        assertEquals(1, results.count { it })
        assertEquals(1000.0, db.budgetDao().getCategoryById(1)!!.currentBalance, 0.001)
    }

    @Test fun actualIncomeMayExceedEstimateAndSplitAcrossCategories()=runBlocking {
        assertTrue(IncomePlanExecution(db).receive(7,month,listOf(1 to 800.0,2 to 450.0),"Actual","above-plan"))
        assertEquals(1250.0,db.budgetDao().getIncomePlanTransactions(1).sumOf { it.amount },0.0)
        val progress=IncomePlanAmounts.progress(db.budgetDao().getIncomeSourceById(7)!!,month,emptyList(),db.budgetDao().getIncomePlanTransactions(1))
        assertEquals(1000.0,progress.planned,0.0)
        assertEquals(1250.0,progress.received,0.0)
        assertEquals(0.0,progress.remaining,0.0)
        assertEquals(800.0,db.budgetDao().getCategoryById(1)!!.currentBalance,0.0)
        assertEquals(450.0,db.budgetDao().getCategoryById(2)!!.currentBalance,0.0)
    }

    @Test fun additionalActualReceiptAllowedAfterEstimateAlreadyReceived()=runBlocking {
        val service=IncomePlanExecution(db)
        assertTrue(service.receive(7,month,listOf(1 to 1000.0),"First","first"))
        assertTrue(service.receive(7,month,listOf(2 to 150.0),"Additional","additional"))
        assertEquals(1150.0,db.budgetDao().getIncomePlanTransactions(1).sumOf { it.amount },0.0)
    }

    @Test fun invalidSecondCategoryRollsBackAllReceiptLines() = runBlocking {
        assertTrue(runCatching { IncomePlanExecution(db).receive(7, month,
            listOf(1 to 400.0, 999 to 600.0), "", "broken") }.isFailure)
        assertTrue(db.budgetDao().getIncomePlanTransactions(1).isEmpty())
        assertEquals(0.0, db.budgetDao().getCategoryById(1)!!.currentBalance, 0.001)
    }

    @Test fun editingAndCancellationUpdateReceivedAmount() = runBlocking {
        val dao = db.budgetDao()
        IncomePlanExecution(db).receive(7, month, listOf(1 to 600.0), "", "receipt")
        val transaction = dao.getIncomePlanTransactions(1).single()
        dao.correctTransaction(transaction.id, 2, 400.0, "income", "Исправлено", "")
        assertEquals(400.0, dao.getIncomePlanTransactions(1).sumOf { it.amount }, 0.001)
        dao.cancelTransactionGroup("receipt")
        assertTrue(dao.getIncomePlanTransactions(1).isEmpty())
        assertEquals(0.0, dao.getCategoryById(2)!!.currentBalance, 0.001)
    }

    @Test fun backupRestoresReceiptSourceWithNewIds() = runBlocking {
        IncomePlanExecution(db).receive(7, month, listOf(1 to 400.0), "", "receipt",finalReceipt=true)
        val original = db.budgetDao().getIncomePlanTransactions(1).single()
        val dao = db.budgetDao()
        val replacement = dao.insertPlannedIncomeSource(dao.getIncomeSourceById(7)!!.copy(id = 0)).toInt()
        BackupAdditionalDataImporter.import(BackupData(transactions = listOf(original)), dao, db.autoDao(),
            mapOf(1 to 1), 1, mapOf(1 to 1), mapOf(original.id.toLong() to original.id.toLong()), mapOf(7 to replacement))
        assertEquals(replacement, dao.getTransactionById(original.id)!!.plannedIncomeSourceId)
        assertTrue(dao.getTransactionById(original.id)!!.incomePlanFinal)
    }

    @Test fun redistributionRetainsOriginalUntilAtomicReplacement() = runBlocking {
        val service = IncomePlanExecution(db)
        service.receive(7, month, listOf(1 to 1000.0), "", "original")
        assertTrue(runCatching { service.receive(7, month, listOf(999 to 1000.0), "", "bad", "original") }.isFailure)
        assertEquals(1000.0, db.budgetDao().getCategoryById(1)!!.currentBalance, 0.001)
        assertTrue(service.receive(7, month, listOf(2 to 1000.0), "", "replacement", "original"))
        assertEquals(0.0, db.budgetDao().getCategoryById(1)!!.currentBalance, 0.001)
        assertEquals(1000.0, db.budgetDao().getCategoryById(2)!!.currentBalance, 0.001)
        assertEquals(1, db.budgetDao().getIncomePlanTransactions(1).size)
    }
}
