package ru.mybudget.app

import android.view.LayoutInflater
import android.widget.ArrayAdapter
import android.widget.ListView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton

object CategoryMultiPicker {
    fun leafLabel(category: BudgetCategory, parents: Map<Int, String>): String {
        val parent = parents[category.parentId]
        return if (parent.isNullOrBlank()) category.name else "$parent → ${category.name}"
    }

    fun show(
        activity: AppCompatActivity,
        leaves: List<BudgetCategory>,
        parents: Map<Int, String>,
        alreadySelected: Set<Int>,
        onPicked: (List<Int>) -> Unit,
    ) {
        val available = leaves.filter { it.id !in alreadySelected }
        if (available.isEmpty()) {
            AlertDialog.Builder(activity)
                .setMessage(R.string.distribution_no_categories_left)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_pick_distribution_categories, null)
        val list = view.findViewById<ListView>(R.id.categoryPickList)
        val labels = available.map { leafLabel(it, parents) }
        list.adapter = ArrayAdapter(activity, android.R.layout.simple_list_item_multiple_choice, labels)
        list.choiceMode = ListView.CHOICE_MODE_MULTIPLE
        view.findViewById<MaterialButton>(R.id.selectAllCategoriesButton).setOnClickListener {
            for (index in available.indices) list.setItemChecked(index, true)
        }
        view.findViewById<MaterialButton>(R.id.selectDefaultsCategoriesButton).setOnClickListener {
            available.forEachIndexed { index, category ->
                list.setItemChecked(index, category.defaultIncomeAmount > 0.0)
            }
        }
        AlertDialog.Builder(activity)
            .setTitle(R.string.distribution_add_categories)
            .setView(view)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val picked = available.mapIndexedNotNull { index, category ->
                    if (list.isItemChecked(index)) category.id else null
                }
                if (picked.isNotEmpty()) onPicked(picked)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
