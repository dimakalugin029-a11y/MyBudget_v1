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
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), BudgetDatabase::class.java).build()
        val dao = db.budgetDao()
        dao.insertBudgetProfile(BudgetProfileEntity(id = 1, name = "Основной"))
        dao.insertCategory(BudgetCategoryEntity(1, "Продукты", 0))
        dao.insertCategory(BudgetCategoryEntity(2, "Авто", 0))
        dao.insertPlannedIncomeSource(PlannedIncomeSourceEntity(id = 7, budgetId = 1,
            name = "Зарплата", amount = 1000.0, sourceType = "salary", dayOfMonth = 21))
        Unit
    }
    @After fun close() { db.close() }

    @Test fun partialAndDistributedReceiptsShareOneRemainingAmount() = runBlocking {
        val service = IncomePlanExecution(db)
        assertTrue(service.receive(7, month, listOf(1 to 400.0), "Часть", "a"))
        assertTrue(service.receive(7, month, listOf(1 to 200.0, 2 to 400.0), "Остаток", "b"))
        assertFalse(service.receive(7, month, listOf(1 to 400.0), "Повтор", "a"))
        assertEquals(1000.0, db.budgetDao().getIncomePlanTransactions(1).sumOf { it.amount }, 0.001)
        assertEquals(600.0, db.budgetDao().getCategoryById(1)!!.currentBalance, 0.001)
        assertEquals(400.0, db.budgetDao().getCategoryById(2)!!.currentBalance, 0.001)
    }

    @Test fun concurrentFullReceiptsDoNotDoubleCredit() = runBlocking {
        val results = coroutineScope { (1..2).map { n -> async(Dispatchers.IO) {
            runCatching { IncomePlanExecution(db).receive(7, month, listOf(1 to 1000.0), "", "request$n") }.getOrDefault(false)
        } }.awaitAll() }
        assertEquals(1, results.count { it })
        assertEquals(1000.0, db.budgetDao().getCategoryById(1)!!.currentBalance, 0.001)
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
        IncomePlanExecution(db).receive(7, month, listOf(1 to 400.0), "", "receipt")
        val original = db.budgetDao().getIncomePlanTransactions(1).single()
        val dao = db.budgetDao()
        val replacement = dao.insertPlannedIncomeSource(dao.getIncomeSourceById(7)!!.copy(id = 0)).toInt()
        BackupAdditionalDataImporter.import(BackupData(transactions = listOf(original)), dao, db.autoDao(),
            mapOf(1 to 1), 1, mapOf(1 to 1), mapOf(original.id.toLong() to original.id.toLong()), mapOf(7 to replacement))
        assertEquals(replacement, dao.getTransactionById(original.id)!!.plannedIncomeSourceId)
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
