package ru.mybudget.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import ru.mybudget.app.data.AutoRepository
import ru.mybudget.app.data.VehicleEntity
import ru.mybudget.app.data.VehicleInsuranceEntity

object InsuranceReminderNotifier {
    const val CHANNEL_ID = "auto_insurance_channel"
    private val REMIND_DAYS = longArrayOf(30L, 7L)

    fun filterDue(
        insurances: List<VehicleInsuranceEntity>,
        vehicles: List<VehicleEntity>,
        todayEpochDay: Long,
    ): List<Pair<VehicleInsuranceEntity, VehicleEntity?>> {
        return insurances.mapNotNull { insurance ->
            val days = AutoRepository.daysUntil(insurance.endDateEpochDay, todayEpochDay)
            if (days in REMIND_DAYS) {
                insurance to vehicles.firstOrNull { it.id == insurance.vehicleId }
            } else {
                null
            }
        }
    }

    fun notifyDue(context: Context, due: List<Pair<VehicleInsuranceEntity, VehicleEntity?>>) {
        if (due.isEmpty()) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.auto_insurance_notify_channel),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ),
            )
        }
        due.forEachIndexed { index, (insurance, vehicle) ->
            val days = AutoRepository.daysUntil(insurance.endDateEpochDay, java.time.LocalDate.now().toEpochDay())
            val text = context.getString(
                R.string.auto_insurance_notify_message,
                vehicle?.name ?: "",
                AutoActivity.formatDay(insurance.endDateEpochDay),
                days,
            )
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_my_calendar)
                .setContentTitle(context.getString(R.string.auto_insurance_notify_title))
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .build()
            manager.notify(insurance.id + 30_000 + index, notification)
        }
    }
}
