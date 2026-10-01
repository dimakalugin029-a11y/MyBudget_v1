package ru.mybudget.app

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.mybudget.app.setup.ImportCategoryMappingPreferences

class ImportRulesActivity : AppCompatActivity() {
    private lateinit var manager: BudgetManager
    private lateinit var adapter: RulesAdapter
    private var budgetId: Int = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_import_rules)
        manager = BudgetManager.getInstance(this)
        ScreenHeaderHelper.setup(
            this,
            getString(R.string.import_rules_title),
            getString(R.string.main_icon_statistics),
        )
        findViewById<TextView>(R.id.importRulesHint).text = getString(R.string.import_rules_hint)
        adapter = RulesAdapter(::confirmDelete)
        findViewById<RecyclerView>(R.id.importRulesList).apply {
            layoutManager = LinearLayoutManager(this@ImportRulesActivity)
            adapter = this@ImportRulesActivity.adapter
        }
        lifecycleScope.launch {
            budgetId = withContext(Dispatchers.IO) { manager.getActiveBudgetId() }
            reload()
        }
    }

    private fun reload() {
        lifecycleScope.launch {
            val rows = withContext(Dispatchers.IO) {
                val categories = manager.getCategoriesAsync()
                val parents = categories.associate { it.id to it.name }
                ImportCategoryMappingPreferences.getRules(this@ImportRulesActivity, budgetId).map { rule ->
                    val category = categories.firstOrNull { it.id == rule.categoryId }
                    RuleRow(
                        pattern = rule.pattern,
                        categoryName = category?.let { CategoryMultiPicker.leafLabel(it, parents) }
                            ?: getString(R.string.transactions_import_failed),
                    )
                }
            }
            adapter.submit(rows)
            findViewById<TextView>(R.id.importRulesEmpty).visibility =
                if (rows.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private fun confirmDelete(pattern: String) {
        AlertDialog.Builder(this)
            .setTitle(R.string.import_rules_title)
            .setMessage(pattern)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val updated = ImportCategoryMappingPreferences.getRules(this, budgetId)
                    .filterNot { it.pattern == pattern }
                val prefs = getSharedPreferences(BudgetApplication.PREFS_NAME, MODE_PRIVATE)
                prefs.edit()
                    .putString(
                        "import_cat_rules_" + budgetId,
                        updated.joinToString("\u001e") { "${it.pattern}:${it.categoryId}" },
                    )
                    .apply()
                Toast.makeText(this, R.string.import_rules_deleted, Toast.LENGTH_SHORT).show()
                reload()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private data class RuleRow(val pattern: String, val categoryName: String)

    private class RulesAdapter(
        private val onDelete: (String) -> Unit,
    ) : RecyclerView.Adapter<RulesAdapter.Holder>() {
        private var items: List<RuleRow> = emptyList()

        fun submit(data: List<RuleRow>) {
            items = data
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_import_rule_row, parent, false)
            return Holder(view, onDelete)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount(): Int = items.size

        class Holder(
            itemView: View,
            private val onDelete: (String) -> Unit,
        ) : RecyclerView.ViewHolder(itemView) {
            private val titleView = itemView.findViewById<TextView>(R.id.importRulePattern)
            private val categoryView = itemView.findViewById<TextView>(R.id.importRuleCategory)

            fun bind(row: RuleRow) {
                titleView.text = row.pattern
                categoryView.text = row.categoryName
                itemView.setOnLongClickListener {
                    onDelete(row.pattern)
                    true
                }
            }
        }
    }
}
