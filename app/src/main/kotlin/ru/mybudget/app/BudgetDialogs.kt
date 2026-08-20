package ru.mybudget.app

import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

object BudgetDialogs {
    enum class TransactionKind { INCOME, EXPENSE }

    fun showAddCategory(
        activity: AppCompatActivity,
        manager: BudgetManager,
        parent: BudgetCategory? = null,
        onDone: (parentId: Int) -> Unit,
    ) {
        val title = if (parent == null) {
            activity.getString(R.string.budget_add_category_title)
        } else {
            activity.getString(R.string.budget_add_subcategory_title, parent.name)
        }
        val input = nameInput(activity, hint = activity.getString(R.string.budget_category_name_hint))
        AlertDialog.Builder(activity)
            .setTitle(title)
            .setView(wrap(activity, input))
            .setPositiveButton(R.string.save) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) {
                    Toast.makeText(activity, R.string.budget_enter_name_hint, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                activity.lifecycleScope.launch {
                    val created = manager.addCategory(name, parent?.id ?: 0)
                    onDone(created.parentId)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    fun showEditCategory(
        activity: AppCompatActivity,
        manager: BudgetManager,
        category: BudgetCategory,
        onDone: () -> Unit,
    ) {
        val input = nameInput(activity, category.name, activity.getString(R.string.budget_name_hint))
        AlertDialog.Builder(activity)
            .setTitle(R.string.budget_edit_category_title)
            .setView(wrap(activity, input))
            .setPositiveButton(R.string.save) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) {
                    Toast.makeText(activity, R.string.budget_enter_name_hint, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                activity.lifecycleScope.launch {
                    manager.updateCategory(category.id, name, category.plannedAmount)
                    onDone()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    fun confirmDeleteCategory(
        activity: AppCompatActivity,
        manager: BudgetManager,
        category: BudgetCategory,
        onDone: () -> Unit,
    ) {
        if (manager.hasSubcategories(category.id)) {
            Toast.makeText(activity, R.string.budget_delete_subcategories_first, Toast.LENGTH_LONG).show()
            return
        }
        AlertDialog.Builder(activity)
            .setTitle(R.string.budget_delete_category_title)
            .setMessage(activity.getString(R.string.budget_delete_category_message, category.name))
            .setPositiveButton(R.string.budget_profiles_delete) { _, _ ->
                activity.lifecycleScope.launch {
                    manager.removeCategory(category.id)
                    Toast.makeText(activity, R.string.budget_deleted_toast, Toast.LENGTH_SHORT).show()
                    onDone()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    fun showAddTransaction(
        activity: AppCompatActivity,
        manager: BudgetManager,
        category: BudgetCategory,
        kind: TransactionKind,
        onDone: () -> Unit,
    ) {
        val input = EditText(activity).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            hint = activity.getString(R.string.budget_amount_hint)
            setPadding(dp(activity, 16), dp(activity, 16), dp(activity, 16), dp(activity, 16))
        }
        val title = if (kind == TransactionKind.INCOME) {
            R.string.budget_add_income_title
        } else {
            R.string.budget_add_expense_title
        }
        AlertDialog.Builder(activity)
            .setTitle(title)
            .setMessage(category.name)
            .setView(wrap(activity, input))
            .setPositiveButton(R.string.save) { _, _ ->
                val amount = MoneyFormat.parse(input.text)
                if (amount == null || amount <= 0.0) {
                    Toast.makeText(activity, R.string.error_invalid_amount, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val type = if (kind == TransactionKind.INCOME) "income" else "expense"
                val description = activity.getString(
                    if (kind == TransactionKind.INCOME) R.string.transaction_income else R.string.transaction_expense,
                )
                activity.lifecycleScope.launch {
                    manager.recordTransaction(category.id, amount, type, description)
                    val toastRes = if (kind == TransactionKind.INCOME) {
                        R.string.budget_income_recorded
                    } else {
                        R.string.budget_expense_recorded
                    }
                    val updated = manager.getCategoryById(category.id)
                    Toast.makeText(
                        activity,
                        activity.getString(toastRes, MoneyFormat.formatRub(updated?.currentBalance ?: 0.0)),
                        Toast.LENGTH_SHORT,
                    ).show()
                    onDone()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun nameInput(activity: AppCompatActivity, value: String = "", hint: String): EditText {
        return EditText(activity).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setText(value)
            setSelection(text.length)
            this.hint = hint
            setPadding(dp(activity, 16), dp(activity, 16), dp(activity, 16), dp(activity, 16))
        }
    }

    private fun wrap(activity: AppCompatActivity, child: EditText): LinearLayout {
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(activity, 8), dp(activity, 8), dp(activity, 8), 0)
            addView(child)
        }
    }

    private fun dp(activity: AppCompatActivity, value: Int): Int {
        return (value * activity.resources.displayMetrics.density).toInt()
    }
}
