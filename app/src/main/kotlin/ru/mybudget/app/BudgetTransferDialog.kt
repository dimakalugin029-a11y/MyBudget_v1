package ru.mybudget.app

import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.mybudget.app.data.BudgetProfileEntity

object BudgetTransferDialog {
    private data class LeafOption(
        val category: BudgetCategory,
        val label: String,
    )

    fun show(
        activity: AppCompatActivity,
        budgetManager: BudgetManager,
        preselectedFromCategoryId: Int? = null,
        onSuccess: () -> Unit = {},
    ) {
        activity.lifecycleScope.launch(Dispatchers.IO) {
            budgetManager.getCategoriesAsync()
            val profiles = budgetManager.getBudgetProfilesAsync()
            val all = budgetManager.getCategories()
            val leafByBudget = profiles.associate { profile ->
                profile.id to buildLeafOptions(all, profile.id, budgetManager)
            }
            val hasAnyLeaf = leafByBudget.values.any { it.isNotEmpty() }
            withContext(Dispatchers.Main) {
                if (profiles.isEmpty() || !hasAnyLeaf) {
                    Toast.makeText(activity, R.string.budget_transfer_no_subcategories, Toast.LENGTH_LONG).show()
                } else {
                    openDialog(activity, budgetManager, profiles, leafByBudget, preselectedFromCategoryId, onSuccess)
                }
            }
        }
    }

    private fun buildLeafOptions(
        all: List<BudgetCategory>,
        budgetId: Int,
        budgetManager: BudgetManager,
    ): List<LeafOption> {
        val parents = all.associate { it.id to it.name }
        return all.filter { category ->
            category.budgetId == budgetId &&
                category.isActive &&
                category.parentId != 0 &&
                !budgetManager.hasSubcategories(category.id)
        }.sortedWith(compareBy({ it.parentId }, { it.name })).map { category ->
            val parent = parents[category.parentId] ?: "?"
            LeafOption(category, "$parent → ${category.name}")
        }
    }

    private fun openDialog(
        activity: AppCompatActivity,
        budgetManager: BudgetManager,
        profiles: List<BudgetProfileEntity>,
        leafByBudget: Map<Int, List<LeafOption>>,
        preselectedFromCategoryId: Int?,
        onSuccess: () -> Unit,
    ) {
        val inflate = activity.layoutInflater.inflate(R.layout.dialog_transfer_budget, null)
        val info = inflate.findViewById<TextView>(R.id.transferBudgetInfoText)
        val fromFixed = inflate.findViewById<TextView>(R.id.transferFromFixedText)
        val fromPickers = inflate.findViewById<View>(R.id.transferFromPickersGroup)
        val fromBudgetSpinner = inflate.findViewById<Spinner>(R.id.fromBudgetSpinner)
        val fromSubSpinner = inflate.findViewById<Spinner>(R.id.fromSubcategorySpinner)
        val toBudgetSpinner = inflate.findViewById<Spinner>(R.id.toBudgetSpinner)
        val toSubSpinner = inflate.findViewById<Spinner>(R.id.toSubcategorySpinner)
        val amountInput = inflate.findViewById<EditText>(R.id.transferBudgetAmountInput)
        val names = profiles.map { it.name }
        val budgetAdapter = ArrayAdapter(activity, android.R.layout.simple_spinner_item, names).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        fromBudgetSpinner.adapter = budgetAdapter
        toBudgetSpinner.adapter = budgetAdapter
        var fromLeaves = emptyList<LeafOption>()
        var toLeaves = emptyList<LeafOption>()
        var lockedFrom: BudgetCategory? = null

        fun bindSubcategorySpinner(spinner: Spinner, options: List<LeafOption>) {
            val adapter = ArrayAdapter(
                activity,
                android.R.layout.simple_spinner_item,
                options.map { it.label },
            ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            spinner.adapter = adapter
        }

        fun updateFromInfo() {
            val from = lockedFrom
                ?: fromLeaves.getOrNull(fromSubSpinner.selectedItemPosition)?.category
            info.text = if (from != null) {
                activity.getString(
                    R.string.budget_transfer_available,
                    from.name,
                    MoneyFormat.formatRub(from.currentBalance),
                )
            } else {
                activity.getString(R.string.budget_transfer_pick_from)
            }
        }

        fun refreshFromSubcategories() {
            val profile = profiles.getOrNull(fromBudgetSpinner.selectedItemPosition) ?: return
            fromLeaves = leafByBudget[profile.id].orEmpty()
            bindSubcategorySpinner(fromSubSpinner, fromLeaves)
            updateFromInfo()
        }

        fun refreshToSubcategories() {
            val profile = profiles.getOrNull(toBudgetSpinner.selectedItemPosition) ?: return
            toLeaves = leafByBudget[profile.id].orEmpty()
            bindSubcategorySpinner(toSubSpinner, toLeaves)
        }

        fromBudgetSpinner.onItemSelectedListener = simpleItemSelected { refreshFromSubcategories() }
        fromSubSpinner.onItemSelectedListener = simpleItemSelected { updateFromInfo() }
        toBudgetSpinner.onItemSelectedListener = simpleItemSelected { refreshToSubcategories() }
        refreshFromSubcategories()
        refreshToSubcategories()

        val preselected = preselectedFromCategoryId?.let { id ->
            leafByBudget.values.flatten().firstOrNull { it.category.id == id }
        }
        if (preselected != null) {
            lockedFrom = preselected.category
            fromPickers.visibility = View.GONE
            fromFixed.visibility = View.VISIBLE
            fromFixed.text = preselected.label
            val budgetIndex = profiles.indexOfFirst { it.id == preselected.category.budgetId }
            if (budgetIndex >= 0) {
                fromBudgetSpinner.setSelection(budgetIndex)
                refreshFromSubcategories()
                val leafIndex = fromLeaves.indexOfFirst { it.category.id == preselected.category.id }
                if (leafIndex >= 0) fromSubSpinner.setSelection(leafIndex)
                toBudgetSpinner.setSelection(budgetIndex)
                refreshToSubcategories()
                updateFromInfo()
            }
        }
        if (lockedFrom == null && profiles.size > 1 &&
            toBudgetSpinner.selectedItemPosition == fromBudgetSpinner.selectedItemPosition
        ) {
            toBudgetSpinner.setSelection((fromBudgetSpinner.selectedItemPosition + 1) % profiles.size)
            refreshToSubcategories()
        }

        AlertDialog.Builder(activity)
            .setTitle(R.string.budget_transfer_title)
            .setPositiveButton(R.string.budget_transfer_confirm, null)
            .setNegativeButton(android.R.string.cancel, null)
            .showWithIme(inflate, arrayOf(amountInput)) { dialog ->
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    val amount = MoneyFormat.parse(amountInput.text)
                    val from = lockedFrom ?: fromLeaves.getOrNull(fromSubSpinner.selectedItemPosition)?.category
                    val to = toLeaves.getOrNull(toSubSpinner.selectedItemPosition)?.category
                    when {
                        amount == null || amount <= 0.0 -> {
                            Toast.makeText(activity, R.string.budget_transfer_amount_required, Toast.LENGTH_SHORT).show()
                        }
                        from == null || to == null -> {
                            Toast.makeText(activity, R.string.budget_transfer_pick_both, Toast.LENGTH_SHORT).show()
                        }
                        from.id == to.id -> {
                            Toast.makeText(activity, R.string.budget_transfer_same_subcategory, Toast.LENGTH_SHORT).show()
                        }
                        else -> {
                            activity.lifecycleScope.launch(Dispatchers.IO) {
                                val ok = budgetManager.transferSubcategoryBalance(from.id, to.id, amount)
                                withContext(Dispatchers.Main) {
                                    if (ok) {
                                        dialog.dismiss()
                                        BudgetWidgetProvider.updateAll(activity)
                                        onSuccess()
                                        Toast.makeText(activity, R.string.budget_transfer_done, Toast.LENGTH_SHORT).show()
                                    } else {
                                        Toast.makeText(activity, R.string.budget_transfer_failed, Toast.LENGTH_LONG).show()
                                    }
                                }
                            }
                        }
                    }
                }
            }
    }

    private fun simpleItemSelected(onSelected: () -> Unit): AdapterView.OnItemSelectedListener {
        return object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                onSelected()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }
}
