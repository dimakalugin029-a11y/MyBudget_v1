package ru.mybudget.app

import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

open class LayoutActivity(
    private val layoutRes: Int,
    private val titleRes: Int = 0,
) : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(layoutRes)
        val title = if (titleRes != 0) getString(titleRes) else ""
        ScreenHeaderHelper.setup(this, title)
    }
}

class HelpActivity : LayoutActivity(R.layout.activity_help, R.string.help_title)
class RemainderDistributionActivity : LayoutActivity(R.layout.activity_remainder_distribution, R.string.remainder_submit)
class StatisticsActivity : LayoutActivity(R.layout.activity_statistics, R.string.statistics_title)
class RecurringActivity : LayoutActivity(R.layout.activity_recurring, R.string.recurring_add_title)
class UtilityMetersActivity : LayoutActivity(R.layout.activity_utility_meters, R.string.utility_meters_title)
class UtilityMeterVerificationActivity : LayoutActivity(R.layout.activity_meter_verification, R.string.meter_verification_title)
class UtilityMeterHistoryActivity : LayoutActivity(R.layout.activity_utility_meter_history)
class UtilityMetersBatchActivity : LayoutActivity(R.layout.activity_utility_meters_batch)
class UtilityCompareActivity : LayoutActivity(R.layout.activity_utility_compare, R.string.utility_compare_title)
class LockActivity : LayoutActivity(R.layout.activity_lock, R.string.lock_title)
class PlanFactActivity : LayoutActivity(R.layout.activity_plan_fact, R.string.plan_fact_title)
class MonthStartActivity : LayoutActivity(R.layout.activity_month_start, R.string.month_start_title)
class RolloverActivity : LayoutActivity(R.layout.activity_rollover, R.string.rollover_title)
class PaymentCalendarActivity : LayoutActivity(R.layout.activity_payment_calendar, R.string.payment_calendar_title)

class BudgetWidgetProvider : AppWidgetProvider() {
    companion object {
        const val ACTION_WIDGET_REFRESH = "ru.mybudget.app.action.WIDGET_REFRESH"

        fun updateAll(context: Context) {
            val intent = Intent(context, BudgetWidgetProvider::class.java).setAction(ACTION_WIDGET_REFRESH)
            context.sendBroadcast(intent)
        }
    }
}

class RecurringActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) = Unit
}
