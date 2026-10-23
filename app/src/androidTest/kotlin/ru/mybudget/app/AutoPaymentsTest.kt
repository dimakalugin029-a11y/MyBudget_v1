package ru.mybudget.app

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import ru.mybudget.app.auto.AutoEntry
import ru.mybudget.app.auto.AutoEntryType
import ru.mybudget.app.auto.AutoPaymentMethod
import ru.mybudget.app.data.*

@RunWith(AndroidJUnit4::class)
class AutoPaymentsTest {
    private lateinit var db: BudgetDatabase
    private lateinit var repository: AutoRepository
    private var vehicle = 0

    @Before fun prepare() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), BudgetDatabase::class.java).addCallback(ru.mybudget.app.data.BalanceLedgerSchema.callback).build()
        repository = AutoRepository(db)
        db.budgetDao().insertBudgetProfile(BudgetProfileEntity(id = 1, name = "Test"))
        db.budgetDao().insertCategory(BudgetCategoryEntity(1, "Parent", 0, currentBalance = 1000.0))
        db.budgetDao().insertCategory(BudgetCategoryEntity(2, "Child", 1, currentBalance = 1000.0))
        vehicle = db.autoDao().insertVehicle(VehicleEntity(name = "Test car")).toInt()
    }
    @After fun close() = db.close()

    private fun entry(type: AutoEntryType) = AutoEntry(type, vehicleId = vehicle,
        dateEpochDay = 20000, amount = 100.0, paymentMethod = AutoPaymentMethod.UNPAID,
        endDateEpochDay = 20365, liters = 2.0, pricePerLiter = 50.0)

    @Test fun fuelMetadataSurvivesPaymentChangeAndCorrection()=runBlocking {
        val id=repository.saveEntry(entry(AutoEntryType.FUEL).copy(fuelType="АИ-95",fuelStation="Station"))
        var current=repository.getEntries().single()
        repository.saveEntry(current.copy(paymentMethod=AutoPaymentMethod.BUDGET,categoryId=2))
        current=repository.getEntries().single()
        assertEquals(id,current.id);assertEquals("АИ-95",current.fuelType);assertEquals("Station",current.fuelStation)
        assertEquals(900.0,db.budgetDao().getCategoryById(2)!!.currentBalance,0.0)
    }
    @Test fun osagoAndCascoCoexistAndKeepIndependentFieldsAndPayments()=runBlocking {
        val osago=repository.saveEntry(entry(AutoEntryType.INSURANCE).copy(paymentMethod=AutoPaymentMethod.CASH,policyNumber="O-001"))
        val casco=repository.saveEntry(entry(AutoEntryType.INSURANCE).copy(insuranceType="casco",insuranceCompany="Company",policyNumber="K-002",insurancePhone="+7 123",paymentMethod=AutoPaymentMethod.BUDGET,categoryId=2))
        assertNotEquals(osago,casco)
        val rows=repository.getEntries()
        assertEquals(2,rows.size)
        val current=rows.single { it.id==casco }
        assertEquals("casco",current.insuranceType);assertEquals("K-002",current.policyNumber);assertEquals("+7 123",current.insurancePhone)
        repository.saveEntry(current.copy(insuranceCompany="Changed"))
        assertEquals("O-001",repository.getEntries().single { it.id==osago }.policyNumber)
        assertEquals(1,db.budgetDao().getAllTransactionsOnce().size)
    }

    @Test fun allEventTypesSupportCashBudgetAndUnpaidTransitions() = runBlocking {
        for (type in AutoEntryType.entries) {
            val id = repository.saveEntry(entry(type))
            var current = repository.getEntries().single { it.type == type && it.id == id }
            repository.saveEntry(current.copy(paymentMethod = AutoPaymentMethod.CASH))
            assertEquals(1000.0, db.budgetDao().getCategoryById(2)!!.currentBalance, 0.0)
            assertTrue(db.budgetDao().getAllTransactionsOnce().isEmpty())
            current = repository.getEntries().single { it.type == type && it.id == id }
            assertTrue(current.paid)
            repository.saveEntry(current.copy(paymentMethod = AutoPaymentMethod.BUDGET, categoryId = 2))
            assertEquals(900.0, db.budgetDao().getCategoryById(1)!!.currentBalance, 0.0)
            repository.saveEntry(current.copy(paymentMethod = AutoPaymentMethod.BUDGET, categoryId = 2, amount = 150.0))
            assertEquals(850.0, db.budgetDao().getCategoryById(2)!!.currentBalance, 0.0)
            assertEquals(1, db.budgetDao().getAllTransactionsOnce().size)
            repository.saveEntry(current.copy(paymentMethod = AutoPaymentMethod.UNPAID))
            assertEquals(1000.0, db.budgetDao().getCategoryById(1)!!.currentBalance, 0.0)
            assertTrue(db.budgetDao().getAllTransactionsOnce().isEmpty())
        }
    }

    @Test fun changingBudgetCategoryMovesTheExpense() = runBlocking {
        db.budgetDao().insertCategory(BudgetCategoryEntity(3, "Other", 0, currentBalance = 500.0))
        repository.saveEntry(entry(AutoEntryType.SERVICE).copy(paymentMethod = AutoPaymentMethod.BUDGET, categoryId = 2))
        val old = repository.getEntries().single()
        repository.saveEntry(old.copy(amount = 250.0, categoryId = 3))
        assertEquals(1000.0, db.budgetDao().getCategoryById(1)!!.currentBalance, 0.0)
        assertEquals(250.0, db.budgetDao().getCategoryById(3)!!.currentBalance, 0.0)
        val tx = db.budgetDao().getAllTransactionsOnce().single()
        assertEquals(3, tx.categoryId)
        assertEquals(tx.id.toLong(), repository.getEntries().single().paidTransactionId)
    }

    @Test fun eventWriteFailureRollsBackItsExpenseAndBalances() = runBlocking {
        db.openHelper.writableDatabase.execSQL("""
            CREATE TRIGGER reject_auto_insert BEFORE INSERT ON vehicle_service_logs
            BEGIN SELECT RAISE(ABORT, 'forced failure'); END
        """.trimIndent())
        assertTrue(runCatching {
            repository.saveEntry(entry(AutoEntryType.SERVICE).copy(paymentMethod = AutoPaymentMethod.BUDGET, categoryId = 2))
        }.isFailure)
        assertTrue(repository.getEntries().isEmpty())
        assertTrue(db.budgetDao().getAllTransactionsOnce().isEmpty())
        assertEquals(1000.0, db.budgetDao().getCategoryById(1)!!.currentBalance, 0.0)
    }

    @Test fun cashPaymentSurvivesBackupAndRestore() = runBlocking {
        repository.saveEntry(entry(AutoEntryType.FUEL).copy(paymentMethod = AutoPaymentMethod.CASH))
        val backup = BackupManager(ApplicationProvider.getApplicationContext<Context>(), db)
        val json = backup.exportToJson()
        assertTrue(backup.importFromJson(json).success)
        val restored = repository.getEntries().single()
        assertEquals(AutoPaymentMethod.CASH, restored.paymentMethod)
        assertTrue(restored.paid)
        assertNull(restored.paidTransactionId)
        assertTrue(db.budgetDao().getAllTransactionsOnce().isEmpty())
    }

    @Test fun repeatedCommandsNeverDuplicateAnyEventTypeOrPayment() = runBlocking {
        for (type in AutoEntryType.entries) {
            val request = "create-${type.name}"
            val draft = entry(type).copy(paymentMethod = AutoPaymentMethod.BUDGET, categoryId = 2)
            val id = repository.saveEntry(draft, request)
            assertEquals(id, repository.saveEntry(draft.copy(amount = 999.0), request))
            assertEquals(1, repository.getEntries().count { it.type == type })
            assertEquals(100.0, repository.getEntries().single { it.type == type }.amount, 0.0)
        }
        assertEquals(4, db.budgetDao().getAllTransactionsOnce().size)
        assertEquals(600.0, db.budgetDao().getCategoryById(2)!!.currentBalance, 0.0)
    }

    @Test fun failedCommandRollsBackAndCanBeRetried() = runBlocking {
        val draft = entry(AutoEntryType.REPAIR).copy(paymentMethod = AutoPaymentMethod.BUDGET, categoryId = 999)
        assertTrue(runCatching { repository.saveEntry(draft, "retry") }.isFailure)
        assertNull(db.autoDao().getSaveCommand("retry"))
        assertTrue(repository.getEntries().isEmpty())
        repository.saveEntry(draft.copy(categoryId = 2), "retry")
        assertEquals(1, db.budgetDao().getAllTransactionsOnce().size)
        assertEquals(900.0, db.budgetDao().getCategoryById(2)!!.currentBalance, 0.0)
    }

    @Test fun retryDoesNotReapplyAnOldEditAfterALaterEdit() = runBlocking {
        val id = repository.saveEntry(entry(AutoEntryType.REPAIR))
        val original = repository.getEntries().single()
        repository.saveEntry(original.copy(amount = 150.0), "edit-1")
        repository.saveEntry(original.copy(amount = 200.0), "edit-2")
        assertEquals(id, repository.saveEntry(original.copy(amount = 150.0), "edit-1"))
        assertEquals(200.0, repository.getEntries().single().amount, 0.0)
    }

    @Test fun concurrentDuplicateRequestsProduceOneCashEvent() = runBlocking {
        val draft = entry(AutoEntryType.SERVICE).copy(paymentMethod = AutoPaymentMethod.CASH)
        val ids = kotlinx.coroutines.coroutineScope {
            List(4) { async(kotlinx.coroutines.Dispatchers.IO) {
                repository.saveEntry(draft, "concurrent")
            } }.map { it.await() }
        }
        assertEquals(1, ids.distinct().size)
        assertEquals(1, repository.getEntries().size)
        assertTrue(db.budgetDao().getAllTransactionsOnce().isEmpty())
    }
}
