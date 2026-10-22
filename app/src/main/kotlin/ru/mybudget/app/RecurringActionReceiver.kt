package ru.mybudget.app

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import ru.mybudget.app.data.BudgetDatabase

class RecurringActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val recurringId = intent?.getIntExtra(RecurringNotification.EXTRA_RECURRING_ID, -1) ?: -1
        if (recurringId <= 0) return
        val expectedDate = intent?.getStringExtra(RecurringNotification.EXTRA_DUE_DATE) ?: return
        val pending = goAsync()
        BudgetApplication.instance.applicationScope.launch(Dispatchers.IO) {
            try {
                val dao = BudgetDatabase.getInstance(context).budgetDao()
                val recurring = dao.getRecurringById(recurringId) ?: return@launch
                if (recurring.nextDueDate != expectedDate) return@launch
                when (intent?.action) {
                    RecurringNotification.ACTION_APPLY -> {
                        RecurringHelper.applyAndAdvance(recurring, context)
                        BudgetManager.getInstance(context).reloadCategoriesFromDatabase()
                    }
                    RecurringNotification.ACTION_SKIP -> {
                        RecurringHelper.skipAndAdvance(recurring, context)
                    }
                }
                val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                manager.cancel(recurringId + 10000)
                BudgetWidgetProvider.updateAll(context)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.os.Handler(context.mainLooper).post {
                    android.widget.Toast.makeText(context, R.string.attention_operation_failed,
                        android.widget.Toast.LENGTH_LONG).show()
                }
            } finally {
                pending.finish()
            }
        }
    }
}
