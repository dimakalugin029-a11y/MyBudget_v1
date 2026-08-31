package ru.mybudget.app

import android.content.Intent
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

object PlanningEntryWizard {
    const val EXTRA_AUTO_ADD = "planning_auto_add"

    fun show(activity: AppCompatActivity) {
        AlertDialog.Builder(activity)
            .setTitle(R.string.planning_wizard_title)
            .setMessage(R.string.planning_wizard_message)
            .setItems(
                arrayOf(
                    activity.getString(R.string.planning_wizard_remind),
                    activity.getString(R.string.planning_wizard_auto),
                    activity.getString(R.string.planning_wizard_obligation),
                ),
            ) { _, which ->
                val target = when (which) {
                    0 -> RemindersActivity::class.java
                    1 -> RecurringActivity::class.java
                    else -> PlannedObligationsActivity::class.java
                }
                activity.startActivity(
                    Intent(activity, target).putExtra(EXTRA_AUTO_ADD, true),
                )
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
