package ru.mybudget.app.setup

import android.content.Context
import ru.mybudget.app.BudgetApplication

object SetupPreferences {
    private const val KEY_SETUP_COMPLETED = "setup_completed"
    private const val KEY_TEMPLATE_ID = "selected_template_id"
    private const val KEY_NOTIFICATION_PERMISSION_ASKED = "notification_permission_asked"

    private fun prefs(context: Context) =
        context.getSharedPreferences(BudgetApplication.PREFS_NAME, Context.MODE_PRIVATE)

    fun isSetupCompleted(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SETUP_COMPLETED, false)

    fun markSetupCompleted(context: Context, templateId: BudgetTemplateId) {
        prefs(context)
            .edit()
            .putBoolean(KEY_SETUP_COMPLETED, true)
            .putString(KEY_TEMPLATE_ID, templateId.name)
            .apply()
    }

    fun isNotificationPermissionAsked(context: Context): Boolean =
        prefs(context).getBoolean(KEY_NOTIFICATION_PERMISSION_ASKED, false)

    fun markNotificationPermissionAsked(context: Context) {
        prefs(context).edit().putBoolean(KEY_NOTIFICATION_PERMISSION_ASKED, true).apply()
    }
}
