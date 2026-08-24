package ru.mybudget.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import ru.mybudget.app.data.UtilityMeterInfoEntity

object MeterVerificationNotifier {
    const val CHANNEL_ID = "meter_verification_channel"
    private const val NOTIFY_DAYS_AHEAD = 30

    fun notifyDueMeters(context: Context, meters: List<UtilityMeterInfoEntity>) {
        if (meters.isEmpty()) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.meter_verification_notify_channel),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ),
            )
        }
        meters.forEachIndexed { index, meter ->
            val dateLabel = meter.verificationDateLabel.ifBlank { "—" }
            val text = context.getString(
                R.string.meter_verification_notify_message,
                meter.meterName,
                dateLabel,
            )
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_my_calendar)
                .setContentTitle(context.getString(R.string.meter_verification_notify_title))
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .build()
            manager.notify(meter.id + 20_000 + index, notification)
        }
    }

    fun filterDueWithinDays(
        meters: List<UtilityMeterInfoEntity>,
        todayEpochDay: Long,
    ): List<UtilityMeterInfoEntity> {
        val maxDay = todayEpochDay + NOTIFY_DAYS_AHEAD
        return meters.filter { meter ->
            val day = meter.verificationEpochDay ?: return@filter false
            todayEpochDay <= day && day <= maxDay
        }
    }
}
