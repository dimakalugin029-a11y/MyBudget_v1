package ru.mybudget.app

import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import ru.mybudget.app.setup.SetupPreferences

object AppNotificationsHelper {
    const val REQUEST_POST_NOTIFICATIONS = 1001
    const val KEY_LAST_EXPORT_PROMPT_MS = "last_export_prompt_ms"
    private const val EXPORT_PROMPT_INTERVAL_MS = 2_592_000_000L

    fun requestNotificationPermissionIfNeeded(activity: Activity) {
        if (Build.VERSION.SDK_INT < 33) return
        if (ContextCompat.checkSelfPermission(activity, android.Manifest.permission.POST_NOTIFICATIONS) == 0) {
            return
        }
        ActivityCompat.requestPermissions(
            activity,
            arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
            REQUEST_POST_NOTIFICATIONS,
        )
    }

    fun maybeRequestNotificationPermissionOnLaunch(activity: Activity) {
        if (Build.VERSION.SDK_INT < 33) return
        if (!SetupPreferences.isSetupCompleted(activity)) return
        if (SetupPreferences.isNotificationPermissionAsked(activity)) return
        if (ContextCompat.checkSelfPermission(activity, android.Manifest.permission.POST_NOTIFICATIONS) == 0) {
            SetupPreferences.markNotificationPermissionAsked(activity)
            return
        }
        AlertDialog.Builder(activity)
            .setMessage(R.string.notification_permission_rationale)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                SetupPreferences.markNotificationPermissionAsked(activity)
                requestNotificationPermissionIfNeeded(activity)
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                SetupPreferences.markNotificationPermissionAsked(activity)
            }
            .show()
    }

    fun maybeShowExportReminder(activity: Activity, prefs: SharedPreferences) {
        val now = System.currentTimeMillis()
        val last = prefs.getLong(KEY_LAST_EXPORT_PROMPT_MS, 0L)
        if (last > 0 && now - last < EXPORT_PROMPT_INTERVAL_MS) return
        AlertDialog.Builder(activity)
            .setTitle("Резервная копия")
            .setMessage(
                "Рекомендуем раз в месяц делать экспорт данных в «Настройки → Экспорт». " +
                    "Так ваши категории и суммы сохранятся, если смените телефон или переустановите приложение.",
            )
            .setPositiveButton("Понятно") { _, _ ->
                prefs.edit().putLong(KEY_LAST_EXPORT_PROMPT_MS, now).apply()
            }
            .setNeutralButton("В настройки") { _, _ ->
                prefs.edit().putLong(KEY_LAST_EXPORT_PROMPT_MS, now).apply()
                activity.startActivity(Intent(activity, SettingsActivity::class.java))
            }
            .setCancelable(true)
            .setOnCancelListener {
                prefs.edit().putLong(KEY_LAST_EXPORT_PROMPT_MS, now).apply()
            }
            .show()
    }
}
