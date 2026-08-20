package ru.mybudget.app

import android.app.DatePickerDialog
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import ru.mybudget.app.data.PaymentReminder
import ru.mybudget.app.setup.RecurringPreferences
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class RemindersActivity : AppCompatActivity() {
    private lateinit var manager: BudgetManager
    private lateinit var adapter: RemindersAdapter
    private val dateFormat = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault())
    private val repeatKeys = listOf("once", "daily", "weekly", "monthly")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_reminders)
        manager = BudgetManager.getInstance(this)
        ScreenHeaderHelper.setup(this, getString(R.string.reminders_title))
        findViewById<View>(R.id.remindersHint)?.let {
            ScreenHintHelper.bind(
                this,
                it,
                ScreenHintHelper.Keys.REMINDERS,
                R.string.hint_reminders,
                showHelpLink = false,
            )
        }
        bindRow(R.id.paymentCalendarButton, R.string.main_icon_reminders, R.string.reminders_payment_calendar) {
            startActivity(Intent(this, PaymentCalendarActivity::class.java))
        }
        bindRow(R.id.recurringButton, R.string.ui_refresh, R.string.reminders_recurring) {
            startActivity(Intent(this, RecurringActivity::class.java))
        }
        val confirmSwitch = findViewById<SwitchCompat>(R.id.recurringConfirmSwitch)
        confirmSwitch.isChecked = RecurringPreferences.isConfirmBeforeApply(this)
        confirmSwitch.setOnCheckedChangeListener { _, checked ->
            RecurringPreferences.setConfirmBeforeApply(this, checked)
        }
        adapter = RemindersAdapter()
        findViewById<RecyclerView>(R.id.remindersRecyclerView).apply {
            layoutManager = LinearLayoutManager(this@RemindersActivity)
            this.adapter = this@RemindersActivity.adapter
        }
        findViewById<View>(R.id.addReminderButton).setOnClickListener { showReminderDialog(null) }
        lifecycleScope.launch {
            manager.getCategoriesAsync()
            manager.repository.getAllReminders().collectLatest { list ->
                val names = manager.getCategories().associate { it.id to it.name }
                val rows = list.map { reminder ->
                    reminder.copy(categoryName = names[reminder.categoryId.toInt()].orEmpty())
                }
                adapter.submit(rows)
                val empty = rows.isEmpty()
                findViewById<View>(R.id.remindersRecyclerView).visibility = if (empty) View.GONE else View.VISIBLE
                findViewById<View>(R.id.remindersEmptyState).visibility = if (empty) View.VISIBLE else View.GONE
            }
        }
    }

    private fun bindRow(includeId: Int, iconRes: Int, titleRes: Int, onClick: () -> Unit) {
        val row = findViewById<View>(includeId)
        row.findViewById<TextView>(R.id.rowIcon).setText(iconRes)
        row.findViewById<TextView>(R.id.rowTitle).setText(titleRes)
        row.setOnClickListener { onClick() }
    }

    private fun showReminderDialog(existing: PaymentReminder?) {
        val leaves = manager.getCategoriesForExpenses()
        if (leaves.isEmpty()) {
            Toast.makeText(this, R.string.error_no_categories, Toast.LENGTH_LONG).show()
            return
        }
        val view = layoutInflater.inflate(R.layout.dialog_add_reminder, null)
        val titleInput = view.findViewById<EditText>(R.id.reminderTitleInput)
        val amountInput = view.findViewById<EditText>(R.id.reminderAmountInput)
        val categorySpinner = view.findViewById<Spinner>(R.id.reminderCategorySpinner)
        val dateButton = view.findViewById<Button>(R.id.reminderDateButton)
        val repeatSpinner = view.findViewById<Spinner>(R.id.reminderRepeatSpinner)
        val parents = manager.getRootCategories().associate { it.id to it.name }
        categorySpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            leaves.map { CategoryMultiPicker.leafLabel(it, parents) },
        )
        val selectedIndex = leaves.indexOfFirst { it.id == existing?.categoryId?.toInt() }.coerceAtLeast(0)
        categorySpinner.setSelection(selectedIndex)
        val repeatLabels = listOf(
            getString(R.string.reminder_repeat_once),
            getString(R.string.reminder_repeat_daily),
            getString(R.string.reminder_repeat_weekly),
            getString(R.string.reminder_repeat_monthly),
        )
        repeatSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, repeatLabels)
        repeatSpinner.setSelection(repeatKeys.indexOf(existing?.repeatType).coerceAtLeast(0))
        var dueDate = existing?.dueDate ?: Date()
        dateButton.text = dateFormat.format(dueDate)
        dateButton.setOnClickListener {
            val cal = Calendar.getInstance().apply { time = dueDate }
            DatePickerDialog(this, { _, year, month, day ->
                cal.set(year, month, day)
                dueDate = cal.time
                dateButton.text = dateFormat.format(dueDate)
            }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
        }
        existing?.let {
            titleInput.setText(it.title)
            amountInput.setText(MoneyFormat.format(it.amount))
        }
        AlertDialog.Builder(this)
            .setTitle(if (existing == null) R.string.reminder_dialog_new_title else R.string.reminder_edit_title)
            .setView(view)
            .setPositiveButton(R.string.reminder_save) { _, _ ->
                val title = titleInput.text.toString().trim()
                val amount = MoneyFormat.parse(amountInput.text)
                val category = leaves.getOrNull(categorySpinner.selectedItemPosition)
                if (title.isEmpty() || amount == null || amount <= 0.0 || category == null) {
                    Toast.makeText(this, R.string.reminder_fill_required, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val reminder = PaymentReminder(
                    id = existing?.id ?: 0L,
                    title = title,
                    amount = amount,
                    categoryId = category.id.toLong(),
                    categoryName = category.name,
                    dueDate = dueDate,
                    repeatType = repeatKeys.getOrElse(repeatSpinner.selectedItemPosition) { "once" },
                    createdAt = existing?.createdAt ?: Date(),
                )
                lifecycleScope.launch {
                    if (existing == null) manager.repository.insertReminder(reminder)
                    else manager.repository.updateReminder(reminder)
                    Toast.makeText(
                        this@RemindersActivity,
                        if (existing == null) R.string.reminder_saved else R.string.reminder_updated,
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private inner class RemindersAdapter : RecyclerView.Adapter<RemindersAdapter.Holder>() {
        private var items: List<PaymentReminder> = emptyList()

        fun submit(newItems: List<PaymentReminder>) {
            items = newItems
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_reminder_card, parent, false)
            return Holder(view)
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val reminder = items[position]
            holder.title.text = reminder.title
            holder.date.text = dateFormat.format(reminder.dueDate)
            holder.amount.text = reminder.getFormattedAmount()
            holder.category.text = reminder.categoryName
            holder.repeat.text = when (reminder.repeatType) {
                "daily" -> getString(R.string.reminder_repeat_daily)
                "weekly" -> getString(R.string.reminder_repeat_weekly)
                "monthly" -> getString(R.string.reminder_repeat_monthly)
                else -> getString(R.string.reminder_repeat_once)
            }
            val overdue = reminder.isOverdue()
            holder.indicator.setBackgroundColor(
                ContextCompat.getColor(this@RemindersActivity, if (overdue) R.color.expense_red else R.color.income_green),
            )
            holder.itemView.setOnClickListener { showReminderDialog(reminder) }
            holder.pay.setOnClickListener { confirmPaid(reminder) }
            holder.delete.setOnClickListener { confirmDelete(reminder) }
        }

        inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val indicator: View = view.findViewById(R.id.statusIndicator)
            val title: TextView = view.findViewById(R.id.reminderTitle)
            val date: TextView = view.findViewById(R.id.reminderDate)
            val amount: TextView = view.findViewById(R.id.reminderAmount)
            val category: TextView = view.findViewById(R.id.reminderCategory)
            val repeat: TextView = view.findViewById(R.id.reminderRepeat)
            val pay: View = view.findViewById(R.id.payReminderButton)
            val delete: View = view.findViewById(R.id.deleteReminderButton)
        }
    }

    private fun confirmPaid(reminder: PaymentReminder) {
        AlertDialog.Builder(this)
            .setTitle(R.string.reminder_mark_paid_title)
            .setMessage(getString(R.string.reminder_mark_paid_msg, reminder.title, MoneyFormat.format(reminder.amount)))
            .setPositiveButton(R.string.reminder_mark_paid) { _, _ ->
                lifecycleScope.launch {
                    ReminderPaymentHelper.payReminder(manager, reminder.id.toInt())
                    Toast.makeText(this@RemindersActivity, R.string.reminder_paid_done, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmDelete(reminder: PaymentReminder) {
        AlertDialog.Builder(this)
            .setTitle(R.string.reminder_delete_title)
            .setMessage(getString(R.string.reminder_delete_message, reminder.title))
            .setPositiveButton(android.R.string.ok) { _, _ ->
                lifecycleScope.launch {
                    manager.repository.deleteReminder(reminder.id)
                    Toast.makeText(this@RemindersActivity, R.string.reminder_deleted, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
