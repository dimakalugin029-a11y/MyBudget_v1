package ru.mybudget.app

import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DefaultAmountsActivity : AppCompatActivity() {
    private lateinit var manager: BudgetManager
    private lateinit var adapter: DefaultAmountsAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_default_amounts)
        ScreenHeaderHelper.setup(this, getString(R.string.default_amounts_title), getString(R.string.settings_default_amounts_emoji))
        manager = BudgetManager.getInstance(this)
        adapter = DefaultAmountsAdapter { category, amount -> saveDefaultAmount(category, amount) }
        findViewById<RecyclerView>(R.id.defaultAmountsRecycler).apply {
            layoutManager = LinearLayoutManager(this@DefaultAmountsActivity)
            this.adapter = this@DefaultAmountsActivity.adapter
        }
        loadCategories()
    }

    override fun onResume() {
        super.onResume()
        loadCategories()
    }

    private fun loadCategories() {
        val empty = findViewById<TextView>(R.id.defaultAmountsEmpty)
        val recycler = findViewById<RecyclerView>(R.id.defaultAmountsRecycler)
        lifecycleScope.launch {
            manager.getCategoriesAsync()
            val budgetId = manager.getActiveBudgetId()
            val parents = manager.getRootCategories(budgetId).associate { it.id to it.name }
            val leaves = manager.getCategoriesForBudget(budgetId)
                .filter { !manager.hasSubcategories(it.id) }
                .sortedWith(compareBy({ parents[it.parentId].orEmpty() }, { it.name }))
            adapter.update(leaves, parents)
            val isEmpty = leaves.isEmpty()
            empty.visibility = if (isEmpty) View.VISIBLE else View.GONE
            recycler.visibility = if (isEmpty) View.GONE else View.VISIBLE
        }
    }

    private fun saveDefaultAmount(category: BudgetCategory, amount: Double) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                manager.repository.updateDefaultIncomeAmount(category.id, amount)
                manager.getCategoriesAsync(forceReload = true)
            }
            Toast.makeText(
                this@DefaultAmountsActivity,
                getString(R.string.default_amounts_saved, category.name),
                Toast.LENGTH_SHORT,
            ).show()
            loadCategories()
        }
    }

    private class DefaultAmountsAdapter(
        private val onSaveAmount: (BudgetCategory, Double) -> Unit,
    ) : RecyclerView.Adapter<DefaultAmountsAdapter.Holder>() {
        private var categories: List<BudgetCategory> = emptyList()
        private var parentNames: Map<Int, String> = emptyMap()

        fun update(newCategories: List<BudgetCategory>, parents: Map<Int, String>) {
            categories = newCategories
            parentNames = parents
            notifyDataSetChanged()
        }

        class Holder(v: View) : RecyclerView.ViewHolder(v) {
            val categoryName: TextView = v.findViewById(R.id.categoryName)
            val parentCategory: TextView = v.findViewById(R.id.parentCategory)
            val amountInput: EditText = v.findViewById(R.id.amountInput)
            val saveButton: CardView = v.findViewById(R.id.saveButton)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_settings, parent, false)
            return Holder(v)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val category = categories[position]
            holder.categoryName.text = category.name
            holder.itemView.findViewById<TextView>(R.id.categoryType)?.visibility = View.GONE
            if (category.parentId == 0) {
                holder.parentCategory.visibility = View.GONE
            } else {
                val parentName = parentNames[category.parentId].orEmpty()
                holder.parentCategory.text = holder.itemView.context.getString(
                    R.string.default_amounts_parent,
                    parentName,
                )
                holder.parentCategory.visibility = View.VISIBLE
            }
            holder.amountInput.setText(
                if (category.defaultIncomeAmount > 0.0) MoneyFormat.format(category.defaultIncomeAmount) else "",
            )
            holder.amountInput.setOnEditorActionListener { _, actionId, event ->
                val imeDone = actionId == EditorInfo.IME_ACTION_DONE
                val enter = event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
                if (imeDone || enter) {
                    saveFromHolder(holder)
                    true
                } else {
                    false
                }
            }
            holder.saveButton.setOnClickListener { saveFromHolder(holder) }
        }

        private fun saveFromHolder(holder: Holder) {
            val pos = holder.bindingAdapterPosition
            if (pos == RecyclerView.NO_POSITION) return
            val amount = MoneyFormat.parse(holder.amountInput.text)
            if (amount == null || amount <= 0.0) {
                Toast.makeText(holder.itemView.context, R.string.default_amounts_invalid, Toast.LENGTH_SHORT).show()
            } else {
                onSaveAmount(categories[pos], MoneyFormat.roundMoney(amount))
            }
        }

        override fun getItemCount(): Int = categories.size
    }
}
