package ru.mybudget.app

import android.text.InputType
import android.view.LayoutInflater
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
        onDistribute: (BudgetCategory) -> Unit = {},
        onDone: () -> Unit,
    ) {
        if (category.parentId != 0 && category.currentBalance != 0.0) {
            showSubcategoryDeleteWithBalance(activity, manager, category, onDistribute, onDone)
            return
        }
        AlertDialog.Builder(activity)
            .setTitle(R.string.budget_delete_category_title)
            .setMessage(activity.getString(R.string.budget_delete_category_message, category.name))
            .setPositiveButton(R.string.budget_profiles_delete) { _, _ ->
                if (category.parentId == 0) {
                    val total = manager.getCategoryBalanceWithSubcategories(category.id)
                    if (total > 0.0) {
                        Toast.makeText(
                            activity,
                            activity.getString(R.string.budget_transfer_first, MoneyFormat.formatRub(total)),
                            Toast.LENGTH_LONG,
                        ).show()
                        return@setPositiveButton
                    }
                    if (manager.hasSubcategories(category.id)) {
                        Toast.makeText(activity, R.string.budget_delete_subcategories_first, Toast.LENGTH_LONG).show()
                        return@setPositiveButton
                    }
                }
                activity.lifecycleScope.launch {
                    val ok = if (category.parentId == 0) {
                        manager.removeCategory(category.id)
                    } else {
                        manager.deleteSubcategoryWithTransfer(category.id)
                    }
                    if (ok) {
                        Toast.makeText(activity, R.string.budget_deleted_toast, Toast.LENGTH_SHORT).show()
                        onDone()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showSubcategoryDeleteWithBalance(
        activity: AppCompatActivity,
        manager: BudgetManager,
        category: BudgetCategory,
        onDistribute: (BudgetCategory) -> Unit,
        onDone: () -> Unit,
    ) {
        activity.lifecycleScope.launch {
            val all = manager.getCategoriesAsync()
            val parents = all.associate { it.id to it.name }
            val targets = all.filter { leaf ->
                leaf.isActive &&
                    leaf.id != category.id &&
                    leaf.budgetId == category.budgetId &&
                    leaf.parentId != 0 &&
                    !manager.hasSubcategories(leaf.id)
            }.sortedWith(compareBy({ it.parentId }, { it.name }))
            val options = targets.map { leaf ->
                val parent = parents[leaf.parentId] ?: "?"
                "$parent → ${leaf.name}" to leaf.id
            }
            withContext(Dispatchers.Main) {
                if (options.isEmpty()) {
                    Toast.makeText(activity, R.string.budget_delete_no_transfer_targets, Toast.LENGTH_LONG).show()
                    return@withContext
                }
                val dialogView = LayoutInflater.from(activity).inflate(R.layout.dialog_delete_subcategory_transfer, null)
                dialogView.findViewById<TextView>(R.id.deleteSubcategoryMessage).text = activity.getString(
                    R.string.budget_subcategory_balance_message,
                    category.name,
                    MoneyFormat.formatRub(category.currentBalance),
                )
                val spinner = dialogView.findViewById<Spinner>(R.id.deleteSubcategoryTargetSpinner)
                spinner.adapter = ArrayAdapter(
                    activity,
                    android.R.layout.simple_spinner_dropdown_item,
                    options.map { it.first },
                )
                AlertDialog.Builder(activity)
                    .setTitle(R.string.budget_subcategory_balance_title)
                    .setView(dialogView)
                    .setPositiveButton(R.string.budget_delete_transfer_and_remove) { _, _ ->
                        val targetId = options.getOrNull(spinner.selectedItemPosition)?.second ?: return@setPositiveButton
                        activity.lifecycleScope.launch {
                            val ok = manager.deleteSubcategoryWithTransfer(category.id, targetId)
                            if (ok) {
                                Toast.makeText(activity, R.string.budget_deleted_toast, Toast.LENGTH_SHORT).show()
                                onDone()
                            }
                        }
                    }
                    .setNegativeButton(R.string.budget_distribute) { _, _ -> onDistribute(category) }
                    .setNeutralButton(android.R.string.cancel, null)
                    .show()
            }
        }
    }

    fun showAddTransaction(
        activity: AppCompatActivity,
        manager: BudgetManager,
        category: BudgetCategory,
        kind: TransactionKind,
        onDone: () -> Unit,
    ) {
        val dialogView = LayoutInflater.from(activity).inflate(R.layout.dialog_add_budget_transaction, null)
        val amountInput = dialogView.findViewById<EditText>(R.id.addTransactionAmount)
        val descriptionInput = dialogView.findViewById<EditText>(R.id.addTransactionDescription)
        val descriptionLabel = dialogView.findViewById<TextView>(R.id.addTransactionDescriptionLabel)
        if (kind == TransactionKind.INCOME) {
            descriptionLabel.setText(R.string.income_description_label)
            descriptionInput.setHint(R.string.income_description_hint)
        } else {
            descriptionLabel.setText(R.string.expense_description_label)
            descriptionInput.setHint(R.string.expense_description_hint)
        }
        val title = if (kind == TransactionKind.INCOME) {
            R.string.budget_add_income_title
        } else {
            R.string.budget_add_expense_title
        }
        AlertDialog.Builder(activity)
            .setTitle(title)
            .setMessage(category.name)
            .setPositiveButton(R.string.save) { _, _ ->
                val amount = MoneyFormat.parse(amountInput.text)
                if (amount == null || amount <= 0.0) {
                    Toast.makeText(activity, R.string.error_invalid_amount, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val defaultDescription = activity.getString(
                    if (kind == TransactionKind.INCOME) R.string.transaction_income else R.string.transaction_expense,
                )
                val description = descriptionInput.text.toString().trim().ifBlank { defaultDescription }
                val type = if (kind == TransactionKind.INCOME) "income" else "expense"
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
            .showWithIme(dialogView, arrayOf(amountInput, descriptionInput))
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
