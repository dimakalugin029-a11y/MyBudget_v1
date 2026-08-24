package ru.mybudget.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import ru.mybudget.app.data.RecurringTransactionEntity

object RecurringNotification {
    const val CHANNEL_ID = "recurring_confirm_channel"
    const val ACTION_APPLY = "ru.mybudget.app.action.RECURRING_APPLY"
    const val ACTION_SKIP = "ru.mybudget.app.action.RECURRING_SKIP"
    const val EXTRA_RECURRING_ID = "recurring_id"

    fun showConfirmNotification(
        context: Context,
        recurring: RecurringTransactionEntity,
        categoryName: String,
    ) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        ensureChannel(context, manager)
        val sign = if (recurring.type == "income") "+" else "-"
        val title = context.getString(R.string.recurring_notify_title)
        val text = context.getString(
            R.string.recurring_notify_message,
            sign,
            MoneyFormat.format(recurring.amount),
            categoryName,
            recurring.nextDueDate,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_today)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .addAction(0, context.getString(R.string.recurring_notify_apply), actionPendingIntent(context, ACTION_APPLY, recurring.id, 100))
            .addAction(0, context.getString(R.string.recurring_notify_skip), actionPendingIntent(context, ACTION_SKIP, recurring.id, 200))
            .build()
        manager.notify(recurring.id + 10000, notification)
    }

    private fun actionPendingIntent(
        context: Context,
        action: String,
        recurringId: Int,
        requestCode: Int,
    ): PendingIntent {
        val intent = Intent(context, RecurringActionReceiver::class.java)
            .setAction(action)
            .putExtra(EXTRA_RECURRING_ID, recurringId)
        return PendingIntent.getBroadcast(
            context,
            requestCode + recurringId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun ensureChannel(context: Context, manager: NotificationManager) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.recurring_notify_channel),
                NotificationManager.IMPORTANCE_HIGH,
            ),
        )
    }
}
