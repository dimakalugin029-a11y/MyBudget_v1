package ru.mybudget.app

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.mybudget.app.data.BudgetDatabase
import ru.mybudget.app.data.BudgetRepository
import ru.mybudget.app.data.RecurringTransactionEntity
import ru.mybudget.app.setup.RecurringPreferences
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RecurringActivity : AppCompatActivity() {
    private lateinit var repository: BudgetRepository
    private lateinit var manager: BudgetManager
    private lateinit var recycler: RecyclerView
    private lateinit var emptyState: TextView
    private val adapter = RecurringAdapter(
        onExecute = { executeRecurring(it) },
        onDelete = { showDeleteDialog(it) },
    )
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_recurring)
        ScreenHeaderHelper.setup(this, getString(R.string.main_menu_recurring), getString(R.string.main_icon_recurring))
        val confirmSwitch = findViewById<SwitchCompat>(R.id.recurringConfirmSwitch)
        confirmSwitch.isChecked = RecurringPreferences.isConfirmBeforeApply(this)
        confirmSwitch.setOnCheckedChangeListener { _, checked ->
            RecurringPreferences.setConfirmBeforeApply(this, checked)
        }
        repository = BudgetRepository(BudgetDatabase.getInstance(this).budgetDao())
        manager = BudgetManager.getInstance(this)
        recycler = findViewById(R.id.recurringRecycler)
        emptyState = findViewById(R.id.recurringEmptyState)
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter
        findViewById<View>(R.id.addRecurringButton).setOnClickListener { showAddDialog() }
        observeRecurring()
        if (intent.getBooleanExtra(PlanningEntryWizard.EXTRA_AUTO_ADD, false)) {
            intent.removeExtra(PlanningEntryWizard.EXTRA_AUTO_ADD)
            showAddDialog()
        }
    }

    private fun observeRecurring() {
        lifecycleScope.launch {
            repository.getAllRecurring().collectLatest { list ->
                val empty = list.isEmpty()
                recycler.visibility = if (empty) View.GONE else View.VISIBLE
                emptyState.visibility = if (empty) View.VISIBLE else View.GONE
                adapter.submit(list.filter { it.obligationId == null || it.obligationId == 0 })
            }
        }
    }

    private fun showAddDialog() {
        lifecycleScope.launch {
            val cats = manager.getCategoriesAsync().filter { !manager.hasSubcategories(it.id) }
            withContext(Dispatchers.Main) {
                val layout = layoutInflater.inflate(R.layout.dialog_add_recurring, null)
                val amountInput = layout.findViewById<EditText>(R.id.amountInput)
                val descInput = layout.findViewById<EditText>(R.id.descInput)
                val typeSpinner = layout.findViewById<Spinner>(R.id.typeSpinner)
                val categorySpinner = layout.findViewById<Spinner>(R.id.categorySpinner)
                val repeatSpinner = layout.findViewById<Spinner>(R.id.repeatSpinner)
                typeSpinner.adapter = ArrayAdapter(
                    this@RecurringActivity,
                    android.R.layout.simple_spinner_dropdown_item,
                    listOf(getString(R.string.recurring_type_expense), getString(R.string.recurring_type_income)),
                )
                repeatSpinner.adapter = ArrayAdapter(
                    this@RecurringActivity,
                    android.R.layout.simple_spinner_dropdown_item,
                    listOf(
                        getString(R.string.reminder_repeat_daily),
                        getString(R.string.reminder_repeat_weekly),
                        getString(R.string.reminder_repeat_monthly),
                    ),
                )
                categorySpinner.adapter = ArrayAdapter(
                    this@RecurringActivity,
                    android.R.layout.simple_spinner_dropdown_item,
                    cats.map { it.name },
                )
                AlertDialog.Builder(this@RecurringActivity)
                    .setTitle(R.string.recurring_add_title)
                    .setPositiveButton(R.string.budget_add_category_btn) { _, _ ->
                        val amount = MoneyFormat.parse(amountInput.text)
                        if (amount == null || amount <= 0.0 || cats.isEmpty()) return@setPositiveButton
                        val type = if (typeSpinner.selectedItemPosition == 0) "expense" else "income"
                        val repeat = when (repeatSpinner.selectedItemPosition) {
                            0 -> "daily"
                            1 -> "weekly"
                            else -> PlannedObligationHelper.PERIOD_MONTHLY
                        }
                        val category = cats.getOrNull(categorySpinner.selectedItemPosition) ?: return@setPositiveButton
                        val nextDate = dateFormat.format(Date())
                        lifecycleScope.launch(Dispatchers.IO) {
                            repository.insertRecurring(
                                RecurringTransactionEntity(
                                    categoryId = category.id,
                                    amount = MoneyFormat.roundMoney(amount),
                                    type = type,
                                    description = descInput.text.toString(),
                                    repeatType = repeat,
                                    nextDueDate = nextDate,
                                ),
                            )
                        }
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .showWithIme(layout, arrayOf(amountInput, descInput))
            }
        }
    }

    private fun executeRecurring(recurring: RecurringTransactionEntity) {
        AlertDialog.Builder(this)
            .setTitle(R.string.recurring_execute_title)
            .setMessage(getString(R.string.recurring_execute_msg, MoneyFormat.format(recurring.amount)))
            .setPositiveButton(R.string.recurring_execute) { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    RecurringHelper.applyAndAdvance(
                        repository,
                        BudgetDatabase.getInstance(this@RecurringActivity).budgetDao(),
                        recurring,
                        this@RecurringActivity,
                    )
                    manager.getCategoriesAsync(forceReload = true)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@RecurringActivity, R.string.recurring_executed, Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showDeleteDialog(recurring: RecurringTransactionEntity) {
        AlertDialog.Builder(this)
            .setTitle(R.string.recurring_delete_title)
            .setMessage(R.string.recurring_delete_message)
            .setPositiveButton(R.string.budget_profiles_delete) { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    repository.deleteRecurring(recurring.id)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private class RecurringAdapter(
        private val onExecute: (RecurringTransactionEntity) -> Unit,
        private val onDelete: (RecurringTransactionEntity) -> Unit,
    ) : RecyclerView.Adapter<RecurringAdapter.Holder>() {
        private var items: List<RecurringTransactionEntity> = emptyList()

        fun submit(newItems: List<RecurringTransactionEntity>) {
            items = newItems
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val view = LayoutInflater.from(parent.context)
                .inflate(android.R.layout.simple_list_item_2, parent, false)
            return Holder(view)
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = items[position]
            val context = holder.itemView.context
            val description = item.description.ifBlank { context.getString(R.string.stats_no_description) }
            val sign = if (item.type == "income") "+" else "-"
            holder.title.text = "$sign ${MoneyFormat.format(item.amount)} ₽ — $description"
            val repeatLabel = when (item.repeatType) {
                "daily" -> context.getString(R.string.reminder_repeat_daily)
                "weekly" -> context.getString(R.string.reminder_repeat_weekly)
                else -> context.getString(R.string.reminder_repeat_monthly)
            }
            holder.subtitle.text = context.getString(R.string.recurring_item_subtitle, item.nextDueDate, repeatLabel)
            holder.itemView.setOnClickListener { onExecute(item) }
            holder.itemView.setOnLongClickListener {
                onDelete(item)
                true
            }
        }

        class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val title: TextView = view.findViewById(android.R.id.text1)
            val subtitle: TextView = view.findViewById(android.R.id.text2)
        }
    }
}
