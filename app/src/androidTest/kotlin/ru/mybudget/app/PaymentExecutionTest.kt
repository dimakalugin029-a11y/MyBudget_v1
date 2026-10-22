package ru.mybudget.app

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import ru.mybudget.app.data.*
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class PaymentExecutionTest {
    private lateinit var db: BudgetDatabase
    private lateinit var payments: PaymentExecution
    private val due = LocalDate.of(2026, 10, 21)

    @Before fun prepare() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), BudgetDatabase::class.java).addCallback(ru.mybudget.app.data.BalanceLedgerSchema.callback).build()
        payments = PaymentExecution(db)
        val dao = db.budgetDao()
        dao.insertBudgetProfile(BudgetProfileEntity(id = 1, name = "Test"))
        dao.insertCategory(BudgetCategoryEntity(1, "Связь", 0, currentBalance = 1000.0))
        dao.insertPlannedObligation(PlannedObligationEntity(id = 7, budgetId = 1, name = "Интернет",
            amount = 100.0, periodType = "monthly", categoryId = 1, paychecksPerMonth = 1, dueMonth = 1, dueDay = 21))
        dao.insertReminder(PaymentReminderEntity(id = 11, title = "Интернет", amount = 100.0,
            categoryId = 1, dueDate = due.toString(), repeatType = "monthly", obligationId = 7))
        dao.insertRecurring(RecurringTransactionEntity(id = 12, categoryId = 1, amount = 100.0,
            type = "expense", repeatType = "monthly", nextDueDate = due.toString(), obligationId = 7))
        Unit
    }
    @After fun close() = db.close()

    private suspend fun assertSettledOnce() {
        val dao = db.budgetDao()
        assertEquals(1, dao.getAllTransactionsOnce().size)
        assertEquals(900.0, dao.getCategoryById(1)!!.currentBalance, 0.0)
        assertTrue(dao.isObligationPeriodPaid(7, 2026, 10))
        assertEquals("2026-11-21", dao.getReminderById(11)!!.dueDate)
        assertEquals("2026-11-21", dao.getRecurringById(12)!!.nextDueDate)
        assertFalse(payments.obligation(7, due, true))
        assertFalse(payments.reminder(11, due.toString(), true))
        assertFalse(payments.recurring(12, due.toString()))
        assertEquals(1, dao.getAllTransactionsOnce().size)
    }

    @Test fun payingObligationSynchronizesAllSources() = runBlocking {
        assertTrue(payments.obligation(7, due, true))
        assertSettledOnce()
    }
    @Test fun payingReminderSynchronizesAllSources() = runBlocking {
        assertTrue(payments.reminder(11, due.toString(), true))
        assertSettledOnce()
    }
    @Test fun applyingAutoOperationSynchronizesAllSources() = runBlocking {
        assertTrue(payments.recurring(12, due.toString()))
        assertSettledOnce()
    }
    @Test fun competingEntryPointsCommitOnlyOneExpense() = runBlocking {
        val outcomes = coroutineScope {
            listOf(async(Dispatchers.Default) { payments.obligation(7, due, true) },
                async(Dispatchers.Default) { payments.recurring(12, due.toString()) }).map { it.await() }
        }
        assertEquals(1, outcomes.count { it })
        assertSettledOnce()
    }
    @Test fun scheduleWriteFailureRollsBackExpensePaymentMarkAndBalance() = runBlocking {
        db.openHelper.writableDatabase.execSQL("""
            CREATE TRIGGER reject_schedule BEFORE UPDATE ON recurring_transactions
            BEGIN SELECT RAISE(ABORT, 'forced failure'); END
        """.trimIndent())
        assertTrue(runCatching { payments.obligation(7, due, true) }.isFailure)
        assertTrue(db.budgetDao().getAllTransactionsOnce().isEmpty())
        assertFalse(db.budgetDao().isObligationPeriodPaid(7, 2026, 10))
        assertEquals(1000.0, db.budgetDao().getCategoryById(1)!!.currentBalance, 0.0)
        assertEquals(due.toString(), db.budgetDao().getReminderById(11)!!.dueDate)
    }
    @Test fun markDoneDoesNotPostAnExpense() = runBlocking {
        assertTrue(payments.reminder(11, due.toString(), false))
        assertTrue(db.budgetDao().getAllTransactionsOnce().isEmpty())
        assertTrue(db.budgetDao().isObligationPeriodPaid(7, 2026, 10))
        assertFalse(payments.recurring(12, due.toString()))
    }
    @Test fun standaloneRepeatedReminderRejectsStaleDate() = runBlocking {
        db.budgetDao().insertReminder(PaymentReminderEntity(id = 30, title = "Другой платёж", amount = 20.0,
            categoryId = 1, dueDate = due.toString(), repeatType = "monthly"))
        assertTrue(payments.reminder(30, due.toString(), true))
        assertFalse(payments.reminder(30, due.toString(), true))
        assertEquals(1, db.budgetDao().getAllTransactionsOnce().size)
    }
}
