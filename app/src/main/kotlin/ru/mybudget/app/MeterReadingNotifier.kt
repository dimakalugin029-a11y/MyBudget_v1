package ru.mybudget.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat

object MeterReadingNotifier {
    const val CHANNEL_ID = "meter_reading_reminder_channel"
    private const val NOTIFICATION_ID = 31_001

    fun show(context: Context) {
        val appContext = context.applicationContext
        val manager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    appContext.getString(R.string.meter_reading_reminder_channel),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ),
            )
        }
        val openBatch = Intent(appContext, UtilityMetersBatchActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            appContext,
            0,
            openBatch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_my_calendar)
            .setContentTitle(appContext.getString(R.string.meter_reading_reminder_title))
            .setContentText(appContext.getString(R.string.meter_reading_reminder_message))
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(appContext.getString(R.string.meter_reading_reminder_message)),
            )
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }
}
