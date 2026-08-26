package ru.mybudget.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import ru.mybudget.app.setup.QuickExpensePreferences

@RunWith(AndroidJUnit4::class)
class QuickExpenseTest {
    @Test
    fun saveLastExpense_enablesRepeat() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        QuickExpensePreferences.saveLastExpense(context, categoryId = 2, amount = 350.0, description = "Обед")
        assertEquals(2, QuickExpensePreferences.getLastCategoryId(context))
        assertEquals(350.0, QuickExpensePreferences.getLastAmount(context)!!, 0.01)
        assertEquals("Обед", QuickExpensePreferences.getLastDescription(context))
        assertTrue(QuickExpensePreferences.hasRepeatableExpense(context))
    }
}
