package ru.mybudget.app

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.mybudget.app.setup.RolloverPreferences

class RolloverActivity : AppCompatActivity() {
    private lateinit var budgetManager: BudgetManager
    private lateinit var adapter: RolloverAdapter
    private var candidates: List<RolloverCandidate> = emptyList()
    private var targetOptions: List<Pair<BudgetCategory, String>> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_rollover)
        ScreenHeaderHelper.setup(this, getString(R.string.rollover_title), getString(R.string.main_icon_budget))
        budgetManager = BudgetManager.getInstance(this)
        adapter = RolloverAdapter()
        findViewById<RecyclerView>(R.id.rolloverRecyclerView).apply {
            layoutManager = LinearLayoutManager(this@RolloverActivity)
            adapter = this@RolloverActivity.adapter
        }
        findViewById<MaterialButton>(R.id.rolloverTransferButton).setOnClickListener { transferSelected() }
        findViewById<MaterialButton>(R.id.rolloverKeepButton).setOnClickListener { finishKeeping() }
        loadData()
    }

    private fun loadData() {
        lifecycleScope.launch(Dispatchers.IO) {
            val all = budgetManager.getCategoriesAsync()
            val budgetId = budgetManager.getActiveBudgetId()
            val parents = all.associate { it.id to it.name }
            val list = BudgetRolloverHelper.candidates(all, parents, budgetId) { budgetManager.hasSubcategories(it) }
            val targets = all
                .filter { cat ->
                    cat.budgetId == budgetId &&
                        cat.isActive &&
                        cat.parentId != 0 &&
                        !budgetManager.hasSubcategories(cat.id)
                }
                .map { cat ->
                    val prefix = if (cat.parentId == 0) "" else (parents[cat.parentId]?.let { "$it → " } ?: "")
                    cat to (prefix + cat.name)
                }
            withContext(Dispatchers.Main) {
                candidates = list
                targetOptions = targets
                adapter.submit(list)
                bindTargetSpinner(targets)
                val hasItems = list.isNotEmpty()
                findViewById<TextView>(R.id.rolloverEmptyText).visibility = if (hasItems) View.GONE else View.VISIBLE
                findViewById<RecyclerView>(R.id.rolloverRecyclerView).visibility =
                    if (hasItems) View.VISIBLE else View.GONE
                findViewById<MaterialButton>(R.id.rolloverTransferButton).isEnabled =
                    hasItems && targets.isNotEmpty()
            }
        }
    }

    private fun bindTargetSpinner(targets: List<Pair<BudgetCategory, String>>) {
        val spinner = findViewById<Spinner>(R.id.rolloverTargetSpinner)
        spinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            targets.map { it.second },
        )
    }

    private fun transferSelected() {
        if (targetOptions.isEmpty() || candidates.isEmpty()) return
        val spinner = findViewById<Spinner>(R.id.rolloverTargetSpinner)
        val target = targetOptions[spinner.selectedItemPosition].first
        val selected = adapter.selectedIds()
        if (selected.isEmpty()) {
            Toast.makeText(this, R.string.rollover_nothing_selected, Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch(Dispatchers.IO) {
            var moved = 0
            for (id in selected) {
                val candidate = candidates.firstOrNull { it.category.id == id } ?: continue
                if (candidate.category.id == target.id) continue
                val ok = budgetManager.transferSubcategoryBalance(
                    candidate.category.id,
                    target.id,
                    MoneyFormat.roundMoney(candidate.balance),
                )
                if (ok) moved++
            }
            RolloverPreferences.markRolloverDone(this@RolloverActivity)
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    this@RolloverActivity,
                    getString(R.string.rollover_done, moved),
                    Toast.LENGTH_LONG,
                ).show()
                finish()
            }
        }
    }

    private fun finishKeeping() {
        RolloverPreferences.markRolloverDone(this)
        Toast.makeText(this, R.string.rollover_kept, Toast.LENGTH_SHORT).show()
        finish()
    }

    private class RolloverAdapter : RecyclerView.Adapter<RolloverAdapter.Holder>() {
        private var items: List<RolloverCandidate> = emptyList()
        private val checked = linkedSetOf<Int>()

        class Holder(v: View) : RecyclerView.ViewHolder(v) {
            val check: CheckBox = v.findViewById(R.id.rolloverCheck)
            val name: TextView = v.findViewById(R.id.rolloverCategoryName)
            val balance: TextView = v.findViewById(R.id.rolloverBalance)
        }

        fun submit(list: List<RolloverCandidate>) {
            items = list
            checked.clear()
            checked.addAll(list.map { it.category.id })
            notifyDataSetChanged()
        }

        fun selectedIds(): Set<Int> = checked.toSet()

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_rollover_line, parent, false)
            return Holder(v)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = items[position]
            holder.name.text = item.label
            holder.balance.text = MoneyFormat.formatRub(item.balance)
            holder.check.setOnCheckedChangeListener(null)
            holder.check.isChecked = checked.contains(item.category.id)
            holder.check.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) checked.add(item.category.id) else checked.remove(item.category.id)
            }
            holder.itemView.setOnClickListener { holder.check.toggle() }
        }

        override fun getItemCount(): Int = items.size
    }
}
