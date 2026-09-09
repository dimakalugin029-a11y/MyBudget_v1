package ru.mybudget.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat

object WeeklySummaryNotifier {
    private const val CHANNEL_ID = "weekly_summary"
    private const val NOTIFICATION_ID = 7001

    fun show(context: Context, digest: WeeklySummaryHelper.Digest) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        ensureChannel(context, manager)
        val openIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(digest.title)
            .setContentText(digest.body.lines().firstOrNull().orEmpty())
            .setStyle(NotificationCompat.BigTextStyle().bigText(digest.body))
            .setContentIntent(openIntent)
            .setAutoCancel(true)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun ensureChannel(context: Context, manager: NotificationManager) {
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.weekly_summary_channel),
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
        )
    }
}
