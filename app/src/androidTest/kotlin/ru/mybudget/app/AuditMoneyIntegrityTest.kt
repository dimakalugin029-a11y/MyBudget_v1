package ru.mybudget.app

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import ru.mybudget.app.data.*
import java.time.LocalDate

class AuditMoneyIntegrityTest {
    private lateinit var db: BudgetDatabase
    @Before fun prepare() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), BudgetDatabase::class.java).build()
        db.budgetDao().insertBudgetProfile(BudgetProfileEntity(id = 1, name = "Test"))
        db.budgetDao().insertCategory(BudgetCategoryEntity(1, "Первый", 0, currentBalance = 1000.0))
        db.budgetDao().insertCategory(BudgetCategoryEntity(2, "Второй", 0))
    }
    @After fun close() { db.close() }

    @Test fun repeatedStaleCancellationRefundsCurrentAmountOnce() = runBlocking {
        val dao = db.budgetDao()
        dao.recordTransaction(1, 100.0, "expense", "")
        val stale = dao.getAllTransactionsOnce().single()
        dao.correctTransaction(stale.id, 1, 200.0, "expense", "", "")
        coroutineScope { listOf(async { dao.revertAndDeleteTransaction(stale) }, async { dao.revertAndDeleteTransaction(stale) }).awaitAll() }
        assertEquals(1000.0, dao.getCategoryById(1)!!.currentBalance, 0.001)
        assertTrue(dao.getAllTransactionsOnce().isEmpty())
    }

    @Test fun transferChecksLatestBalanceAndDeduplicatesRequest() = runBlocking {
        val dao = db.budgetDao()
        dao.transferBetweenLeafCategories(1, 2, 600.0, "same")
        dao.transferBetweenLeafCategories(1, 2, 600.0, "same")
        assertTrue(runCatching { dao.transferBetweenLeafCategories(1, 2, 600.0, "new") }.isFailure)
        assertEquals(400.0, dao.getCategoryById(1)!!.currentBalance, 0.001)
        assertEquals(600.0, dao.getCategoryById(2)!!.currentBalance, 0.001)
    }

    @Test fun invalidDistributionRollsBackEveryRecipient() = runBlocking {
        val dao = db.budgetDao()
        assertTrue(runCatching { dao.distributeBalance(1, intArrayOf(2, 999), doubleArrayOf(200.0, 200.0), "batch") }.isFailure)
        assertEquals(1000.0, dao.getCategoryById(1)!!.currentBalance, 0.001)
        assertEquals(0.0, dao.getCategoryById(2)!!.currentBalance, 0.001)
    }

    @Test fun cancellationReopensObligationAndLinkedSchedule() = runBlocking {
        val dao = db.budgetDao()
        val due = LocalDate.of(2026, 10, 21)
        dao.insertPlannedObligation(PlannedObligationEntity(id = 7, budgetId = 1, name = "Связь", amount = 100.0,
            periodType = "monthly", categoryId = 1, paychecksPerMonth = 1, dueMonth = 1, dueDay = 21))
        dao.insertReminder(PaymentReminderEntity(id = 8, title = "Связь", amount = 100.0, categoryId = 1,
            dueDate = due.toString(), repeatType = "monthly", obligationId = 7))
        PaymentExecution(db).obligation(7, due, true)
        dao.revertAndDeleteTransaction(dao.getAllTransactionsOnce().single())
        assertFalse(dao.isObligationPeriodPaid(7, 2026, 10))
        assertEquals(due.toString(), dao.getReminderById(8)!!.dueDate)
        assertEquals(1000.0, dao.getCategoryById(1)!!.currentBalance, 0.001)
    }

    @Test fun cancellingAutoPaymentMakesEventUnpaid() = runBlocking {
        val vehicle = db.autoDao().insertVehicle(VehicleEntity(name = "Авто")).toInt()
        val repo = AutoRepository(db)
        val id = repo.saveEntry(ru.mybudget.app.auto.AutoEntry(type = ru.mybudget.app.auto.AutoEntryType.REPAIR,
            vehicleId = vehicle, dateEpochDay = LocalDate.now().toEpochDay(), amount = 100.0,
            categoryId = 1, paymentMethod = "budget"))
        db.budgetDao().revertAndDeleteTransaction(db.budgetDao().getAllTransactionsOnce().single())
        assertFalse(repo.getEntries().single { it.id == id }.paid)
    }
}
