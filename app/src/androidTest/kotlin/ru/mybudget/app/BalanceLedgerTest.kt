package ru.mybudget.app

import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import ru.mybudget.app.data.*

@RunWith(AndroidJUnit4::class)
class BalanceLedgerTest {
    private lateinit var db: BudgetDatabase
    private lateinit var context: Context
    private val dao get()=db.budgetDao()
    @Before fun setup()=runBlocking {
        val base=ApplicationProvider.getApplicationContext<Context>()
        val prefix="ledger-test-${java.util.UUID.randomUUID()}-"
        context=object:ContextWrapper(base) {
            override fun getApplicationContext():Context=this
            override fun getSharedPreferences(name:String,mode:Int)=base.getSharedPreferences(prefix+name,mode)
        }
        db=Room.inMemoryDatabaseBuilder(context,BudgetDatabase::class.java).addCallback(BalanceLedgerSchema.callback).build()
        dao.insertBudgetProfile(BudgetProfileEntity(id=1,name="A"))
        dao.insertBudgetProfile(BudgetProfileEntity(id=2,name="B"))
        dao.insertCategory(BudgetCategoryEntity(1,"Parent A",0,budgetId=1,currentBalance=1000.0))
        dao.insertCategory(BudgetCategoryEntity(2,"Child A",1,budgetId=1,currentBalance=1000.0))
        dao.insertCategory(BudgetCategoryEntity(3,"Parent B",0,budgetId=2,currentBalance=500.0))
        dao.insertCategory(BudgetCategoryEntity(4,"Child B",3,budgetId=2,currentBalance=500.0))
    }
    @After fun close()=db.close()
    private suspend fun assertTotal(expected:Double) {
        assertEquals(expected,dao.getAllBalanceMovements().sumOf { it.budgetDelta },0.001)
        assertEquals(expected,dao.getAllCategoriesForExport().filter { it.parentId==0 && it.isActive }.sumOf { it.currentBalance },0.001)
    }
    @Test fun transferIsRecordedOnceAndDoesNotBecomeIncomeOrExpense()=runBlocking {
        assertTotal(1500.0)
        dao.transferBetweenLeafCategories(2,4,100.0,"transfer")
        assertTotal(1500.0)
        assertEquals(900.0,dao.getBalanceMovements(1,Long.MAX_VALUE).sumOf { it.budgetDelta },0.0)
        assertEquals(600.0,dao.getBalanceMovements(2,Long.MAX_VALUE).sumOf { it.budgetDelta },0.0)
        val count=dao.getAllBalanceMovements().size
        dao.transferBetweenLeafCategories(2,4,100.0,"transfer")
        assertEquals(count,dao.getAllBalanceMovements().size)
        assertTrue(dao.getAllTransactionsOnce().isEmpty())
        val transfer=dao.getAllBalanceMovements().filter { it.operationId=="transfer" }
        assertTrue(transfer.isNotEmpty())
        assertTrue(transfer.all { it.kind=="transfer" })
    }
    @Test fun correctionAndCancellationRemainInBalanceHistory()=runBlocking {
        dao.recordTransaction(2,100.0,"expense","Test")
        val tx=dao.getAllTransactionsOnce().single()
        dao.correctTransaction(tx.id,2,125.0,"expense","Changed","")
        assertTotal(1375.0)
        dao.revertAndDeleteTransaction(tx)
        assertTotal(1500.0)
        val rows=dao.getAllBalanceMovements()
        assertTrue(rows.any { it.kind=="correction" })
        assertTrue(rows.any { it.kind=="reversal" })
        assertTrue(dao.getAllTransactionsOnce().isEmpty())
        assertEquals("adjustment",dao.getLedgerState()!!.kind)
    }
    @Test fun rawChangesArchiveAndUpsertCannotBypassLedger()=runBlocking {
        val parent=dao.getCategoryById(1)!!
        dao.updateCategory(parent.copy(isActive=false))
        assertTotal(500.0)
        dao.insertCategory(parent.copy(currentBalance=900.0))
        assertTotal(1400.0)
    }
    @Test fun failedMultiTransferRollsBackAllMovements()=runBlocking {
        val count=dao.getAllBalanceMovements().size
        assertTrue(runCatching { dao.distributeBalance(2,intArrayOf(4,99999),doubleArrayOf(100.0,10.0),"failed") }.isFailure)
        assertEquals(count,dao.getAllBalanceMovements().size)
        assertTotal(1500.0)
    }
    @Test fun legacyRestoreStartsNewCoverageWithoutChangingFact()=runBlocking {
        val backup=BackupManager(context,db)
        val json=JsonParser.parseString(backup.exportToJson()).asJsonObject
        json.remove("balanceLedgerState");json.remove("balanceMovements");json.addProperty("version",24)
        assertTrue(backup.importFromJson(json.toString()).success)
        assertNotNull(dao.getLedgerState())
        assertTotal(1500.0)
        assertTrue(dao.getAllBalanceMovements().all { it.kind=="opening" })
    }
    @Test fun invalidImportedLedgerDoesNotReplaceDatabase()=runBlocking {
        val backup=BackupManager(context,db)
        val json=JsonParser.parseString(backup.exportToJson()).asJsonObject
        json.getAsJsonArray("balanceMovements")[0].asJsonObject.addProperty("budgetDelta",999999.0)
        assertFalse(backup.importFromJson(json.toString()).success)
        assertTotal(1500.0)
        assertEquals(4,dao.getAllCategoriesForExport().size)
    }
    @Test fun replacementRestoresOriginalLedgerCoverageAndAmounts()=runBlocking {
        dao.transferBetweenLeafCategories(2,4,10.0,"saved-transfer")
        val backup=BackupManager(context,db)
        val json=backup.exportToJson()
        val original=dao.getAllBalanceMovements().map { it.budgetDelta }
        val start=dao.getLedgerState()!!.startedAt
        assertTrue(backup.importFromJson(json).success)
        assertEquals(start,dao.getLedgerState()!!.startedAt)
        assertEquals(original,dao.getAllBalanceMovements().map { it.budgetDelta })
        assertTotal(1500.0)
    }
}
