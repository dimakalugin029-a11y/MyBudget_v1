package ru.mybudget.app

import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object MoveSubcategoryDialog {
    fun show(
        activity: AppCompatActivity,
        budgetManager: BudgetManager,
        from: BudgetCategory,
        onSuccess: () -> Unit,
    ) {
        activity.lifecycleScope.launch(Dispatchers.IO) {
            budgetManager.getCategoriesAsync()
            val all = budgetManager.getCategories()
            val targets = all.filter { category ->
                category.budgetId == from.budgetId &&
                    category.isActive &&
                    category.parentId == 0 &&
                    category.id != from.parentId
            }.sortedBy { it.name.lowercase() }
            withContext(Dispatchers.Main) {
                if (targets.isEmpty()) {
                    Toast.makeText(activity, R.string.budget_move_no_targets, Toast.LENGTH_LONG).show()
                } else {
                    openPicker(activity, budgetManager, from, targets, onSuccess)
                }
            }
        }
    }

    private fun openPicker(
        activity: AppCompatActivity,
        budgetManager: BudgetManager,
        from: BudgetCategory,
        targets: List<BudgetCategory>,
        onSuccess: () -> Unit,
    ) {
        val labels = targets.map { category ->
            "${category.name} (${MoneyFormat.formatRub(category.currentBalance)})"
        }.toTypedArray()
        ItemsDialogHelper.show(
            activity,
            title = activity.getString(R.string.budget_move_title),
            message = activity.getString(
                R.string.budget_move_pick_message,
                from.name,
                MoneyFormat.formatRub(from.currentBalance),
            ),
            items = labels,
            negativeText = activity.getString(android.R.string.cancel),
        ) { position ->
            val target = targets.getOrNull(position) ?: return@show
            confirmMove(activity, budgetManager, from, target, onSuccess)
        }
    }

    private fun confirmMove(
        activity: AppCompatActivity,
        budgetManager: BudgetManager,
        from: BudgetCategory,
        target: BudgetCategory,
        onSuccess: () -> Unit,
    ) {
        AlertDialog.Builder(activity)
            .setTitle(R.string.budget_move_confirm_title)
            .setMessage(
                activity.getString(
                    R.string.budget_move_confirm_message,
                    from.name,
                    MoneyFormat.formatRub(from.currentBalance),
                    target.name,
                ),
            )
            .setPositiveButton(R.string.budget_move_confirm_ok) { _, _ ->
                activity.lifecycleScope.launch(Dispatchers.IO) {
                    val ok = budgetManager.moveSubcategoryToCategory(from.id, target.id)
                    withContext(Dispatchers.Main) {
                        if (ok) {
                            BudgetWidgetProvider.updateAll(activity)
                            onSuccess()
                            Toast.makeText(activity, R.string.budget_move_done, Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(activity, R.string.budget_move_failed, Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
