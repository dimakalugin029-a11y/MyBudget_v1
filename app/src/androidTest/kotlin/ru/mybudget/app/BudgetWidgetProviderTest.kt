package ru.mybudget.app

import android.widget.FrameLayout
import android.widget.RemoteViews
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BudgetWidgetProviderTest {
    companion object {
        private lateinit var seed: ScreenTestSeed.SeedData

        @BeforeClass
        @JvmStatic
        fun prepareApp() {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            seed = ScreenTestSeed.prepare(context)
        }
    }

    @Test
    fun widgetContent_showsProfileNameAndFormattedBalance() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val manager = BudgetManager.getInstance(context)
        val expectedName = manager.getBudgetProfilesAsync()
            .let { profiles -> profiles.firstOrNull { it.id == manager.getActiveBudgetId() }?.name }
            .orEmpty()
            .ifBlank { context.getString(R.string.budget_profiles_default_name) }
        val expectedBalance = MoneyFormat.formatRub(manager.getTotalBalance())

        val content = BudgetWidgetViews.loadContent(context)

        assertEquals(expectedName, content.profileName)
        assertEquals(expectedBalance, content.balanceText)
        assertTrue(content.balanceText.contains("₽") || content.balanceText.contains("руб"))
    }

    @Test
    fun widgetContent_updatesAfterIncomeTransaction() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val manager = BudgetManager.getInstance(context)
        val before = BudgetWidgetViews.loadContent(context).balanceText

        manager.recordTransaction(
            categoryId = seed.categoryId,
            amount = 100.0,
            type = "income",
            description = "widget-test",
        )

        val after = BudgetWidgetViews.loadContent(context).balanceText
        assertNotEquals("Balance should change after income", before, after)
        assertEquals(MoneyFormat.formatRub(manager.getTotalBalance()), after)
    }

    @Test
    fun widgetRemoteViews_inflatesWithApply() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val parent = FrameLayout(context)
        RemoteViews(context.packageName, R.layout.widget_budget).apply(context, parent)
    }

    @Test
    fun widgetRemoteViews_buildsWithoutError() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val content = BudgetWidgetViews.loadContent(context)
        val views = BudgetWidgetViews.buildRemoteViews(context, content)
        assertNotNull(views)
    }

    @Test
    fun widgetLayout_hasQuickActionButtons() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val root = android.view.LayoutInflater.from(context)
            .inflate(R.layout.widget_budget, FrameLayout(context), false)

        assertTrue(root.findViewById<TextView>(R.id.widgetIncomeButton).text.isNotBlank())
        assertTrue(root.findViewById<TextView>(R.id.widgetExpenseButton).text.isNotBlank())
        assertTrue(root.findViewById<TextView>(R.id.widgetBudgetName).text.isNotBlank())
        assertTrue(root.findViewById<TextView>(R.id.widgetBalance).text.isNotBlank())
    }

    @Test
    fun widgetProviderRefresh_doesNotCrash() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        context.sendBroadcast(
            android.content.Intent(BudgetWidgetProvider.ACTION_REFRESH)
                .setComponent(
                    android.content.ComponentName(context, BudgetWidgetProvider::class.java),
                ),
        )
        Thread.sleep(1000)
    }
}
