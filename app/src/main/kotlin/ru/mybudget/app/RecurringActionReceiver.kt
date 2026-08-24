package ru.mybudget.app

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import ru.mybudget.app.data.BudgetDatabase
import ru.mybudget.app.data.BudgetRepository

class RecurringActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val recurringId = intent?.getIntExtra(RecurringNotification.EXTRA_RECURRING_ID, -1) ?: -1
        if (recurringId <= 0) return
        val pending = goAsync()
        BudgetApplication.instance.applicationScope.launch(Dispatchers.IO) {
            try {
                val dao = BudgetDatabase.getInstance(context).budgetDao()
                val recurring = dao.getRecurringById(recurringId) ?: return@launch
                val repository = BudgetRepository(dao)
                when (intent?.action) {
                    RecurringNotification.ACTION_APPLY -> {
                        RecurringHelper.applyAndAdvance(repository, dao, recurring, context)
                        BudgetManager.getInstance(context).reloadCategoriesFromDatabase()
                    }
                    RecurringNotification.ACTION_SKIP -> {
                        RecurringHelper.skipAndAdvance(dao, recurring)
                    }
                }
                val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                manager.cancel(recurringId + 10000)
                BudgetWidgetProvider.updateAll(context)
            } finally {
                pending.finish()
            }
        }
    }
}
