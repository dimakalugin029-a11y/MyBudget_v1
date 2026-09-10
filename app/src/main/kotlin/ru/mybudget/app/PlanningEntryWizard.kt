package ru.mybudget.app

import android.content.Intent
import androidx.appcompat.app.AppCompatActivity

object PlanningEntryWizard {
    const val EXTRA_AUTO_ADD = "planning_auto_add"

    fun show(activity: AppCompatActivity) {
        ItemsDialogHelper.show(
            context = activity,
            title = activity.getString(R.string.planning_wizard_title),
            message = activity.getString(R.string.planning_wizard_message),
            items = arrayOf(
                activity.getString(R.string.planning_wizard_remind),
                activity.getString(R.string.planning_wizard_auto),
                activity.getString(R.string.planning_wizard_obligation),
            ),
            negativeText = activity.getString(android.R.string.cancel),
        ) { which ->
            val target = when (which) {
                0 -> RemindersActivity::class.java
                1 -> RecurringActivity::class.java
                else -> PlannedObligationsActivity::class.java
            }
            activity.startActivity(
                Intent(activity, target).putExtra(EXTRA_AUTO_ADD, true),
            )
        }
    }
}
