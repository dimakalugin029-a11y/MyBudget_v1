package ru.mybudget.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import ru.mybudget.app.backup.AutoBackupScheduler

class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != ACTION_QUICKBOOT) return
        AutoBackupScheduler.ensureScheduled(context)
        ReminderScheduler.ensureScheduled(context)
        BudgetWidgetProvider.updateAll(context)
    }

    companion object {
        private const val ACTION_QUICKBOOT = "android.intent.action.QUICKBOOT_POWERON"
    }
}
