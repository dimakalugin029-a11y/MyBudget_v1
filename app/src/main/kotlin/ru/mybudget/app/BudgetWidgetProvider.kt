package ru.mybudget.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
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

        internal const val WIDGET_PENDING_FLAGS =
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
            val views = BudgetWidgetViews.buildRemoteViews(context, BudgetWidgetViews.loadContent(context))
            manager.updateAppWidget(widgetId, views)
        }
    }
}
