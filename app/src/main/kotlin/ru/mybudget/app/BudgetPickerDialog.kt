package ru.mybudget.app

import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object BudgetPickerDialog {
    fun bind(
        activity: AppCompatActivity,
        budgetManager: BudgetManager,
        nameView: TextView,
        pickerView: View,
        getSelectedBudgetId: () -> Int,
        onBudgetSelected: (Int) -> Unit,
    ) {
        refreshName(activity, budgetManager, getSelectedBudgetId(), nameView)
        pickerView.setOnClickListener {
            show(activity, budgetManager, getSelectedBudgetId()) { id, name ->
                onBudgetSelected(id)
                nameView.text = name
            }
        }
    }

    fun refreshName(
        activity: AppCompatActivity,
        budgetManager: BudgetManager,
        budgetId: Int,
        nameView: TextView,
    ) {
        activity.lifecycleScope.launch {
            val profiles = withContext(Dispatchers.IO) { budgetManager.getBudgetProfilesAsync() }
            val name = profiles.firstOrNull { it.id == budgetId }?.name
                ?: activity.getString(R.string.budget_profiles_default_name)
            nameView.text = name
        }
    }

    fun show(
        activity: AppCompatActivity,
        budgetManager: BudgetManager,
        selectedBudgetId: Int,
        onSelected: (Int, String) -> Unit,
    ) {
        activity.lifecycleScope.launch {
            val profiles = withContext(Dispatchers.IO) { budgetManager.getBudgetProfileTotals() }
            if (profiles.isEmpty()) return@launch
            val labels = profiles.map { (profile, total) ->
                val mark = if (profile.id == selectedBudgetId) "✓ " else "   "
                "$mark${profile.name} — ${MoneyFormat.formatRub(total)}"
            }.toTypedArray()
            AlertDialog.Builder(activity)
                .setTitle(R.string.budget_picker_title)
                .setItems(labels) { _, which ->
                    val selected = profiles[which].first
                    if (selected.id != selectedBudgetId) {
                        onSelected(selected.id, selected.name)
                    }
                }
                .show()
        }
    }

    fun showWithAllBudgetsOption(
        activity: AppCompatActivity,
        budgetManager: BudgetManager,
        selectedBudgetId: Int,
        onSelected: (Int) -> Unit,
    ) {
        show(activity, budgetManager, selectedBudgetId) { id, _ -> onSelected(id) }
    }
}
