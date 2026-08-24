package ru.mybudget.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.graphics.Color
import android.os.Build
import androidx.core.app.NotificationCompat
import ru.mybudget.app.data.PaymentReminder

class ReminderNotification(private val context: Context) {
    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    init {
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Напоминания о платежах",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Уведомления о предстоящих платежах"
            enableLights(true)
            lightColor = Color.RED
            enableVibration(true)
        }
        notificationManager.createNotificationChannel(channel)
    }

    fun showReminderNotification(reminder: PaymentReminder) {
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Напоминание: ${reminder.title}")
            .setContentText("Сумма: ${reminder.getFormattedAmount()} • ${reminder.categoryName}")
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "Не забудьте оплатить ${reminder.title} на сумму ${reminder.getFormattedAmount()}\n" +
                        "Категория: ${reminder.categoryName}\nСрок: ${reminder.dueDate}",
                ),
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        notificationManager.notify(reminder.id.toInt(), notification)
    }

    fun cancelReminderNotification(reminderId: Long) {
        notificationManager.cancel(reminderId.toInt())
    }

    companion object {
        const val CHANNEL_ID = "payment_reminders_channel"
    }
}
