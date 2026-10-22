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
import ru.mybudget.app.data.*
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class PaymentEventLoaderTest {
    private lateinit var db: BudgetDatabase
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val today = LocalDate.of(2026, 10, 21)
    @Before fun prepare() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(context, BudgetDatabase::class.java).addCallback(ru.mybudget.app.data.BalanceLedgerSchema.callback).build()
        for (id in 1..2) {
            db.budgetDao().insertBudgetProfile(BudgetProfileEntity(id = id, name = "Budget $id"))
            db.budgetDao().insertCategory(BudgetCategoryEntity(id, "Category $id", 0, budgetId = id))
            db.budgetDao().insertReminder(PaymentReminderEntity(id = id, title = "Reminder $id", amount = 100.0,
                categoryId = id, dueDate = today.toString(), repeatType = "once"))
        }
    }
    @After fun close() = db.close()

    @Test fun selectedBudgetCannotSeeAnotherBudgetsReminder() = runBlocking {
        val first = PaymentEventLoader.load(context, 1, today = today, database = db)
        val second = PaymentEventLoader.load(context, 2, today = today, database = db)
        assertEquals(listOf(1), first.entries.mapNotNull { it.sourceRef.reminderId })
        assertEquals(listOf(2), second.entries.mapNotNull { it.sourceRef.reminderId })
    }

    @Test fun monthlyIncomeOverrideAndDisabledMonthAreRespected() = runBlocking {
        val id = db.budgetDao().insertPlannedIncomeSource(PlannedIncomeSourceEntity(budgetId = 1,
            name = "Salary", amount = 1000.0, sourceType = "salary", dayOfMonth = 22)).toInt()
        db.budgetDao().upsertMonthlyIncomePlan(MonthlyIncomePlanEntity(2026, 10, id, 1, 1500.0, true))
        val adjusted = PaymentEventLoader.load(context, 1, horizonDays = 6, today = today, database = db)
        assertEquals(1500.0, adjusted.entries.single { it.sourceRef.incomeSourceId == id }.amount!!, 0.0)
        db.budgetDao().upsertMonthlyIncomePlan(MonthlyIncomePlanEntity(2026, 10, id, 1, 1500.0, false))
        val disabled = PaymentEventLoader.load(context, 1, horizonDays = 6, today = today, database = db)
        assertTrue(disabled.entries.none { it.sourceRef.incomeSourceId == id })
    }
}
