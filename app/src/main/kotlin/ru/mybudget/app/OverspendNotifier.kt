package ru.mybudget.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.mybudget.app.data.BudgetDatabase
import ru.mybudget.app.setup.OverspendPreferences
import java.util.Calendar

object OverspendNotifier {
    private const val CHANNEL_ID = "overspend_alerts"

    suspend fun checkAfterExpense(context: Context, categoryId: Int) {
        if (!OverspendPreferences.isEnabled(context)) return
        val dao = BudgetDatabase.getInstance(context).budgetDao()
        val category = dao.getCategoryById(categoryId) ?: return
        if (category.plannedAmount <= 0.0) return

        val monthStart = Calendar.getInstance().apply {
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val spent = dao.getExpenseSumForCategorySince(categoryId, monthStart)
        val threshold = OverspendPreferences.getThresholdPercent(context) / 100.0
        val limit = category.plannedAmount * threshold
        if (spent < limit) return

        val pct = if (category.plannedAmount > 0.0) {
            ((spent / category.plannedAmount) * 100.0).toInt()
        } else {
            0
        }
        withContext(Dispatchers.Main) {
            showNotification(
                context,
                category.name,
                MoneyFormat.formatRub(spent),
                MoneyFormat.formatRub(category.plannedAmount),
                pct,
            )
        }
    }

    private fun showNotification(
        context: Context,
        categoryName: String,
        spent: String,
        planned: String,
        percent: Int,
    ) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.overspend_settings_title),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ),
            )
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(context.getString(R.string.overspend_notify_title))
            .setContentText(
                context.getString(R.string.overspend_notify_message, categoryName, spent, planned, percent),
            )
            .setAutoCancel(true)
            .build()
        manager.notify(categoryName.hashCode(), notification)
    }
}
