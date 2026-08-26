package ru.mybudget.app

import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AllActivitiesSmokeTest {
    companion object {
        private lateinit var seed: ScreenTestSeed.SeedData

        @BeforeClass
        @JvmStatic
        fun prepareApp() {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            seed = ScreenTestSeed.prepare(context)
        }
    }

    private fun smokeLaunch(
        activityClass: Class<out AppCompatActivity>,
        configure: (Intent) -> Unit = {},
    ) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(context, activityClass).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            configure(this)
        }
        ActivityScenario.launch<AppCompatActivity>(intent).use {
            Thread.sleep(1500)
        }
    }

    @Test fun welcomeActivity() = smokeLaunch(WelcomeActivity::class.java)

    @Test fun mainActivity() = smokeLaunch(MainActivity::class.java)

    @Test fun aboutActivity() = smokeLaunch(AboutActivity::class.java)

    @Test fun helpActivity() = smokeLaunch(HelpActivity::class.java)

    @Test fun settingsActivity() = smokeLaunch(SettingsActivity::class.java)

    @Test fun defaultAmountsActivity() = smokeLaunch(DefaultAmountsActivity::class.java)

    @Test fun expensePlanActivity() = smokeLaunch(ExpensePlanActivity::class.java)

    @Test fun budgetProfilesActivity() = smokeLaunch(BudgetProfilesActivity::class.java)

    @Test fun budgetActivity() = smokeLaunch(BudgetActivity::class.java)

    @Test fun incomeActivity() = smokeLaunch(IncomeActivity::class.java)

    @Test fun incomeDistributionActivity() = smokeLaunch(IncomeDistributionActivity::class.java) {
        it.putExtra(IncomeDistributionActivity.EXTRA_TOTAL_INCOME, 1000.0)
        it.putExtra(BudgetIntentExtras.BUDGET_ID, 1)
    }

    @Test fun remainderDistributionActivity() = smokeLaunch(RemainderDistributionActivity::class.java) {
        it.putExtra(RemainderDistributionActivity.EXTRA_SOURCE_CATEGORY_ID, seed.categoryId)
        it.putExtra(RemainderDistributionActivity.EXTRA_SOURCE_CATEGORY_NAME, seed.categoryName)
        it.putExtra(RemainderDistributionActivity.EXTRA_AVAILABLE_AMOUNT, 500.0)
    }

    @Test fun expenseActivity() = smokeLaunch(ExpenseActivity::class.java)

    @Test fun quickExpenseActivity() = smokeLaunch(QuickExpenseActivity::class.java)

    @Test fun expenseDistributionActivity() = smokeLaunch(ExpenseDistributionActivity::class.java) {
        it.putExtra(ExpenseDistributionActivity.EXTRA_TOTAL_EXPENSE, 500.0)
        it.putExtra(BudgetIntentExtras.BUDGET_ID, 1)
    }

    @Test fun transactionsActivity() = smokeLaunch(TransactionsActivity::class.java) {
        it.putExtra(TransactionsActivity.EXTRA_CATEGORY_TITLE, seed.categoryName)
        it.putExtra(TransactionsActivity.EXTRA_CATEGORY_IDS, intArrayOf(seed.categoryId))
    }

    @Test fun statisticsActivity() = smokeLaunch(StatisticsActivity::class.java)

    @Test fun remindersActivity() = smokeLaunch(RemindersActivity::class.java)

    @Test fun goalsActivity() = smokeLaunch(GoalsActivity::class.java)

    @Test fun plannedObligationsActivity() = smokeLaunch(PlannedObligationsActivity::class.java)

    @Test fun participantsReportActivity() = smokeLaunch(ParticipantsReportActivity::class.java)

    @Test fun recurringActivity() = smokeLaunch(RecurringActivity::class.java)

    @Test fun utilitiesActivity() = smokeLaunch(UtilitiesActivity::class.java)

    @Test fun utilityBillActivity() = smokeLaunch(UtilityBillActivity::class.java) {
        it.putExtra(UtilitiesActivity.EXTRA_BILL_ID, seed.billId)
    }

    @Test fun utilityMetersActivity() = smokeLaunch(UtilityMetersActivity::class.java)

    @Test fun utilityMeterVerificationActivity() = smokeLaunch(UtilityMeterVerificationActivity::class.java)

    @Test fun utilityMeterHistoryActivity() = smokeLaunch(UtilityMeterHistoryActivity::class.java) {
        it.putExtra(UtilityMeterHistoryActivity.EXTRA_GROUP, "Test")
        it.putExtra(UtilityMeterHistoryActivity.EXTRA_METER, "Water")
    }

    @Test fun utilityMetersBatchActivity() = smokeLaunch(UtilityMetersBatchActivity::class.java)

    @Test fun utilityTemplateActivity() = smokeLaunch(UtilityTemplateActivity::class.java)

    @Test fun utilityTariffsActivity() = smokeLaunch(UtilityTariffsActivity::class.java)

    @Test fun utilityCompareActivity() = smokeLaunch(UtilityCompareActivity::class.java)

    @Test fun lockActivity() = smokeLaunch(LockActivity::class.java)

    @Test fun planFactActivity() = smokeLaunch(PlanFactActivity::class.java)

    @Test fun monthStartActivity() = smokeLaunch(MonthStartActivity::class.java)

    @Test fun rolloverActivity() = smokeLaunch(RolloverActivity::class.java)

    @Test fun paymentCalendarActivity() = smokeLaunch(PaymentCalendarActivity::class.java)
}
