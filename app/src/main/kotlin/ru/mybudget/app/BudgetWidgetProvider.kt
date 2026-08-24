package ru.mybudget.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BudgetWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val pending = goAsync()
        BudgetApplication.instance.applicationScope.launch(Dispatchers.IO) {
            try {
                appWidgetIds.forEach { id ->
                    updateWidget(context, appWidgetManager, id)
                }
            } finally {
                pending.finish()
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_REFRESH) {
            updateAll(context)
        }
    }

    companion object {
        const val ACTION_REFRESH = "ru.mybudget.app.action.WIDGET_REFRESH"
        const val ACTION_WIDGET_REFRESH = ACTION_REFRESH

        private const val PENDING_FLAGS =
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, BudgetWidgetProvider::class.java))
            if (ids.isEmpty()) return
            BudgetApplication.instance.applicationScope.launch(Dispatchers.IO) {
                ids.forEach { id -> updateWidget(context, manager, id) }
            }
        }

        private suspend fun updateWidget(context: Context, manager: AppWidgetManager, widgetId: Int) {
            val budgetManager = BudgetManager.getInstance(context)
            budgetManager.getCategoriesAsync()
            val activeId = budgetManager.getActiveBudgetId()
            val profiles = budgetManager.getBudgetProfilesAsync()
            val name = profiles.firstOrNull { it.id == activeId }?.name
                ?: context.getString(R.string.budget_profiles_default_name)
            val balance = budgetManager.getTotalBalance(activeId)
            val views = RemoteViews(context.packageName, R.layout.widget_budget)
            views.setTextViewText(R.id.widgetBudgetName, name)
            views.setTextViewText(R.id.widgetBalance, MoneyFormat.formatRub(balance))
            val openBudget = PendingIntent.getActivity(
                context,
                0,
                Intent(context, BudgetActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PENDING_FLAGS,
            )
            views.setOnClickPendingIntent(R.id.widgetRoot, openBudget)
            views.setOnClickPendingIntent(
                R.id.widgetIncomeButton,
                activityPendingIntent(context, IncomeActivity::class.java, 1),
            )
            views.setOnClickPendingIntent(
                R.id.widgetExpenseButton,
                activityPendingIntent(context, ExpenseActivity::class.java, 2),
            )
            manager.updateAppWidget(widgetId, views)
        }

        private fun activityPendingIntent(context: Context, cls: Class<*>, requestCode: Int): PendingIntent {
            val intent = Intent(context, cls).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return PendingIntent.getActivity(context, requestCode, intent, PENDING_FLAGS)
        }
    }
}
