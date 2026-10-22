package ru.mybudget.app

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
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
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), BudgetDatabase::class.java).build()
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
}
