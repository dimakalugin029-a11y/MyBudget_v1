package ru.mybudget.app

import android.content.Intent
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

object BudgetPicker {
    fun show(
        activity: AppCompatActivity,
        onSwitched: () -> Unit,
        allowManage: Boolean = true,
    ) {
        val manager = BudgetManager.getInstance(activity)
        activity.lifecycleScope.launch {
            val totals = manager.getBudgetProfileTotals()
            if (totals.isEmpty()) return@launch
            val names = totals.map { (profile, total) ->
                val mark = if (profile.id == manager.getActiveBudgetId()) " ✓" else ""
                "${profile.name}$mark  ·  ${MoneyFormat.formatRub(total)}"
            }.toTypedArray()
            AlertDialog.Builder(activity)
                .setTitle(R.string.budget_picker_title)
                .setItems(names) { _, which ->
                    val selected = totals[which].first
                    manager.setActiveBudgetId(selected.id)
                    Toast.makeText(
                        activity,
                        activity.getString(R.string.budget_picker_switched, selected.name),
                        Toast.LENGTH_SHORT,
                    ).show()
                    onSwitched()
                }
                .apply {
                    if (allowManage) {
                        setNeutralButton(R.string.budget_profiles_manage) { _, _ ->
                            activity.startActivity(Intent(activity, BudgetProfilesActivity::class.java))
                        }
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }
}
