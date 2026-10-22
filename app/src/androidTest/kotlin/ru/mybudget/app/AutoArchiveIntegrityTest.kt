package ru.mybudget.app

import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import ru.mybudget.app.auto.*
import ru.mybudget.app.data.*

@RunWith(AndroidJUnit4::class)
class AutoArchiveIntegrityTest {
    private lateinit var db:BudgetDatabase
    private lateinit var context:Context
    private lateinit var repository:AutoRepository
    private var car=0
    private val dao get()=db.budgetDao()
    @Before fun prepare()=runBlocking {
        val base=ApplicationProvider.getApplicationContext<Context>()
        val prefix="auto-archive-${java.util.UUID.randomUUID()}-"
        context=object:ContextWrapper(base) {
            override fun getApplicationContext():Context=this
            override fun getSharedPreferences(name:String,mode:Int)=base.getSharedPreferences(prefix+name,mode)
        }
        db=Room.inMemoryDatabaseBuilder(context,BudgetDatabase::class.java).addCallback(BalanceLedgerSchema.callback).build()
        repository=AutoRepository(db)
        dao.insertBudgetProfile(BudgetProfileEntity(id=1,name="Test"))
        dao.insertCategory(BudgetCategoryEntity(1,"Parent",0,currentBalance=1000.0))
        dao.insertCategory(BudgetCategoryEntity(2,"Child",1,currentBalance=1000.0))
        car=repository.insertVehicle("Car")
    }
    @After fun close()=db.close()
    private fun entry(type:AutoEntryType,method:String=AutoPaymentMethod.BUDGET)=AutoEntry(type,
        vehicleId=car,dateEpochDay=20000,amount=100.0,categoryId=2,paymentMethod=method,
        endDateEpochDay=20365,liters=2.0,pricePerLiter=50.0)
    private suspend fun saved(type:AutoEntryType)=repository.getEntries(car).single { it.type==type }
    private suspend fun assertBalance(expected:Double) {
        assertEquals(expected,dao.getCategoryById(1)!!.currentBalance,0.0)
        assertEquals(expected,dao.getCategoryById(2)!!.currentBalance,0.0)
        assertEquals(expected,dao.getAllBalanceMovements().sumOf { it.budgetDelta },0.0)
    }
    @Test fun allTypesArchiveAndRestoreWithoutChangingPaymentOrLedger()=runBlocking {
        for(type in AutoEntryType.entries) repository.saveEntry(entry(type))
        val ledger=dao.getAllBalanceMovements()
        val transactions=dao.getAllTransactionsOnce()
        for(type in AutoEntryType.entries) {
            val current=saved(type)
            repository.setEntryArchived(type,current.id,true)
            assertTrue(saved(type).isArchived)
            assertEquals(current.paidTransactionId,saved(type).paidTransactionId)
            assertTrue(runCatching { repository.saveEntry(current.copy(amount=120.0)) }.isFailure)
            repository.setEntryArchived(type,current.id,false)
            assertEquals(current,saved(type))
        }
        assertEquals(ledger,dao.getAllBalanceMovements())
        assertEquals(transactions,dao.getAllTransactionsOnce())
        assertBalance(600.0)
    }
    @Test fun allTypesCancellationRefundsExactlyOnceAndOldSaveCannotReplay()=runBlocking {
        for(type in AutoEntryType.entries) {
            val draft=entry(type)
            val request="create-${type.name}"
            repository.saveEntry(draft,request)
            val current=saved(type)
            assertBalance(900.0)
            repository.cancelEntry(current)
            repository.cancelEntry(current)
            repository.saveEntry(draft,request)
            assertTrue(repository.getEntries(car).isEmpty())
            assertTrue(dao.getAllTransactionsOnce().isEmpty())
            assertBalance(1000.0)
        }
    }
    @Test fun cashAndUnpaidCancellationNeverRefundCategory()=runBlocking {
        for(type in AutoEntryType.entries) for(method in listOf(AutoPaymentMethod.CASH,AutoPaymentMethod.UNPAID)) {
            repository.saveEntry(entry(type,method))
            repository.cancelEntry(saved(type))
            assertBalance(1000.0)
            assertTrue(dao.getAllTransactionsOnce().isEmpty())
        }
    }
    @Test fun failedEventDeleteRollsBackRefundTransactionAndLedger()=runBlocking {
        repository.saveEntry(entry(AutoEntryType.REPAIR))
        val current=saved(AutoEntryType.REPAIR)
        val movements=dao.getAllBalanceMovements()
        val transactions=dao.getAllTransactionsOnce()
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_auto_delete BEFORE DELETE ON vehicle_repairs BEGIN SELECT RAISE(ABORT, 'test deletion failure'); END")
        assertTrue(runCatching { repository.cancelEntry(current) }.isFailure)
        assertBalance(900.0)
        assertEquals(current,saved(AutoEntryType.REPAIR))
        assertEquals(movements,dao.getAllBalanceMovements())
        assertEquals(transactions,dao.getAllTransactionsOnce())
    }

    @Test fun staleCancellationCannotUndoCorrectedAmount()=runBlocking {
        repository.saveEntry(entry(AutoEntryType.REPAIR))
        val stale=saved(AutoEntryType.REPAIR)
        repository.saveEntry(stale.copy(amount=150.0))
        assertTrue(runCatching { repository.cancelEntry(stale) }.isFailure)
        assertBalance(850.0)
        assertEquals(150.0,dao.getAllTransactionsOnce().single().amount,0.0)
    }
    @Test fun lastCarCanArchiveAndRestoreWithoutCreatingReplacement()=runBlocking {
        repository.saveEntry(entry(AutoEntryType.SERVICE))
        val current=saved(AutoEntryType.SERVICE)
        repository.setVehicleArchived(car,true)
        assertTrue(repository.getAllVehicles().single().isArchived)
        assertEquals(car,repository.ensureDefaultVehicle("Unexpected new car").id)
        assertTrue(runCatching { repository.saveEntry(entry(AutoEntryType.REPAIR)) }.isFailure)
        assertTrue(runCatching { repository.cancelEntry(current) }.isFailure)
        assertTrue(runCatching { repository.setEntryArchived(current.type,current.id,true) }.isFailure)
        assertBalance(900.0)
        repository.setVehicleArchived(car,false)
        assertEquals(current,saved(AutoEntryType.SERVICE))
        repository.cancelEntry(current)
        assertBalance(1000.0)
    }
    @Test fun archivedEntriesSurviveBackupWithPaymentLinksAndStatistics()=runBlocking {
        repository.saveEntry(entry(AutoEntryType.INSURANCE))
        val original=saved(AutoEntryType.INSURANCE)
        repository.setEntryArchived(original.type,original.id,true)
        repository.setVehicleArchived(car,true)
        val backup=BackupManager(context,db)
        val json=backup.exportToJson()
        assertTrue(backup.importFromJson(json).success)
        val restored=repository.getAllVehicles().single()
        assertTrue(restored.isArchived)
        val record=repository.getEntries(restored.id).single()
        assertTrue(record.isArchived)
        assertNotNull(dao.getTransactionById(record.paidTransactionId!!.toInt()))
        assertEquals(100.0,AutoStatisticsHelper.summarize(listOf(record),restored.id,null,null).totalPaid,0.0)
        assertEquals(900.0,dao.getAllBalanceMovements().sumOf { it.budgetDelta },0.0)
    }
}
