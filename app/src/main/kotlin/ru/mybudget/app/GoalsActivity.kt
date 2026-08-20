package ru.mybudget.app

import android.app.DatePickerDialog
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import ru.mybudget.app.data.SavingsGoalEntity
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class GoalsActivity : AppCompatActivity() {
    private lateinit var manager: BudgetManager
    private lateinit var adapter: GoalsAdapter
    private val isoFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    private val displayFormat = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_goals)
        manager = BudgetManager.getInstance(this)
        ScreenHeaderHelper.setup(this, getString(R.string.goals_title), getString(R.string.main_icon_goals))
        findViewById<View>(R.id.goalsHint)?.let {
            ScreenHintHelper.bind(this, it, ScreenHintHelper.Keys.GOALS, R.string.hint_goals, showHelpLink = false)
        }
        adapter = GoalsAdapter(
            onEdit = { showGoalDialog(it) },
            onDelete = { confirmDelete(it) },
        )
        findViewById<RecyclerView>(R.id.goalsRecyclerView).apply {
            layoutManager = LinearLayoutManager(this@GoalsActivity)
            this.adapter = this@GoalsActivity.adapter
        }
        findViewById<View>(R.id.addGoalButton).setOnClickListener { showGoalDialog(null) }
        lifecycleScope.launch {
            manager.getCategoriesAsync()
            manager.repository.getAllSavingsGoals().collectLatest { goals ->
                val rows = goals.map { goal ->
                    val category = manager.getCategories().firstOrNull { it.id == goal.categoryId }
                    GoalRow(
                        goal = goal,
                        currentBalance = category?.let { manager.getCategoryBalanceWithSubcategories(it.id) } ?: 0.0,
                        categoryName = category?.name.orEmpty(),
                    )
                }
                adapter.submit(rows)
                findViewById<View>(R.id.goalsEmptyState).visibility =
                    if (rows.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    private fun showGoalDialog(existing: GoalRow?) {
        val leaves = manager.getCategoriesForExpenses()
        if (leaves.isEmpty()) {
            Toast.makeText(this, R.string.distribution_no_leaf_categories, Toast.LENGTH_LONG).show()
            return
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
        }
        val nameInput = EditText(this).apply {
            hint = getString(R.string.goals_name_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setText(existing?.goal?.name.orEmpty())
        }
        val amountInput = EditText(this).apply {
            hint = getString(R.string.goals_amount_hint)
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            existing?.goal?.targetAmount?.takeIf { it > 0.0 }?.let { setText(MoneyFormat.format(it)) }
        }
        val parents = manager.getRootCategories().associate { it.id to it.name }
        val spinner = Spinner(this)
        spinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            leaves.map { CategoryMultiPicker.leafLabel(it, parents) },
        )
        val selectedIndex = leaves.indexOfFirst { it.id == existing?.goal?.categoryId }.coerceAtLeast(0)
        spinner.setSelection(selectedIndex)
        var deadline = existing?.goal?.deadline
        val deadlineButton = Button(this).apply {
            text = deadline?.let { GoalProgressHelper.formatDeadlineLabel(it) } ?: getString(R.string.goals_pick_deadline)
            setOnClickListener {
                val cal = Calendar.getInstance()
                DatePickerDialog(this@GoalsActivity, { _, year, month, day ->
                    cal.set(year, month, day)
                    deadline = isoFormat.format(cal.time)
                    text = displayFormat.format(cal.time)
                }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
            }
        }
        val clearDeadline = Button(this).apply {
            text = getString(R.string.goals_clear_deadline)
            setOnClickListener {
                deadline = null
                deadlineButton.text = getString(R.string.goals_pick_deadline)
            }
        }
        container.addView(nameInput)
        container.addView(amountInput)
        container.addView(spinner)
        container.addView(deadlineButton)
        container.addView(clearDeadline)
        AlertDialog.Builder(this)
            .setTitle(if (existing == null) R.string.goals_add_title else R.string.goals_edit_title)
            .setView(container)
            .setPositiveButton(R.string.save) { _, _ ->
                val name = nameInput.text.toString().trim()
                val amount = MoneyFormat.parse(amountInput.text)
                if (name.isEmpty() || amount == null || amount <= 0.0) {
                    Toast.makeText(this, R.string.error_invalid_amount, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val category = leaves.getOrNull(spinner.selectedItemPosition) ?: return@setPositiveButton
                lifecycleScope.launch {
                    val entity = SavingsGoalEntity(
                        id = existing?.goal?.id ?: 0,
                        name = name,
                        targetAmount = amount,
                        categoryId = category.id,
                        deadline = deadline,
                    )
                    if (existing == null) manager.repository.insertSavingsGoal(entity)
                    else manager.repository.updateSavingsGoal(entity)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmDelete(row: GoalRow) {
        AlertDialog.Builder(this)
            .setTitle(R.string.goals_delete_title)
            .setMessage(getString(R.string.goals_delete_message, row.goal.name))
            .setPositiveButton(R.string.budget_profiles_delete) { _, _ ->
                lifecycleScope.launch { manager.repository.deleteSavingsGoal(row.goal.id) }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    data class GoalRow(
        val goal: SavingsGoalEntity,
        val currentBalance: Double,
        val categoryName: String,
    )

    private inner class GoalsAdapter(
        private val onEdit: (GoalRow) -> Unit,
        private val onDelete: (GoalRow) -> Unit,
    ) : RecyclerView.Adapter<GoalsAdapter.Holder>() {
        private var items: List<GoalRow> = emptyList()

        fun submit(newItems: List<GoalRow>) {
            items = newItems
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_goal, parent, false)
            return Holder(view)
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val row = items[position]
            val categoryName = row.categoryName.ifBlank {
                getString(R.string.goals_category_fallback, row.goal.categoryId)
            }
            holder.name.text = "${row.goal.name} → $categoryName"
            val progress = GoalProgressHelper.progressPercent(row.currentBalance, row.goal.targetAmount)
            holder.bar.progress = progress
            holder.progress.text = if (GoalProgressHelper.isComplete(row.currentBalance, row.goal.targetAmount)) {
                "${getString(R.string.goals_complete)} • ${MoneyFormat.format(row.currentBalance)} / ${MoneyFormat.format(row.goal.targetAmount)} ₽"
            } else {
                "${MoneyFormat.format(row.currentBalance)} / ${MoneyFormat.format(row.goal.targetAmount)} ₽ ($progress%)"
            }
            val days = GoalProgressHelper.daysUntilDeadline(row.goal.deadline)
            val deadlineLabel = GoalProgressHelper.formatDeadlineLabel(row.goal.deadline)
            if (deadlineLabel.isNullOrBlank()) {
                holder.deadline.visibility = View.GONE
            } else {
                holder.deadline.visibility = View.VISIBLE
                val daysLabel = when {
                    days == null -> ""
                    days == 0 -> getString(R.string.goals_deadline_today)
                    days > 0 -> getString(R.string.goals_days_left, days)
                    else -> getString(R.string.goals_days_overdue, -days)
                }
                holder.deadline.text = getString(R.string.goals_deadline_display, deadlineLabel) +
                    if (daysLabel.isBlank()) "" else " • $daysLabel"
            }
            val monthly = GoalProgressHelper.monthlyContributionNeeded(row.currentBalance, row.goal.targetAmount, days)
            if (monthly != null && monthly > 0.0) {
                holder.monthly.visibility = View.VISIBLE
                holder.monthly.text = getString(R.string.goals_monthly_hint, MoneyFormat.format(monthly))
            } else {
                holder.monthly.visibility = View.GONE
            }
            holder.itemView.setOnClickListener { onEdit(row) }
            holder.edit.setOnClickListener { onEdit(row) }
            holder.delete.setOnClickListener { onDelete(row) }
        }

        inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val name: TextView = view.findViewById(R.id.goalName)
            val bar: ProgressBar = view.findViewById(R.id.goalProgressBar)
            val progress: TextView = view.findViewById(R.id.goalProgress)
            val deadline: TextView = view.findViewById(R.id.goalDeadline)
            val monthly: TextView = view.findViewById(R.id.goalMonthlyHint)
            val edit: View = view.findViewById(R.id.editGoalButton)
            val delete: View = view.findViewById(R.id.deleteGoalButton)
        }
    }
}
