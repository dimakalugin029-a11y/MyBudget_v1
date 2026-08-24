package ru.mybudget.app

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.mybudget.app.data.BudgetDatabase
import ru.mybudget.app.utilities.PaymentCalendarHelper
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Calendar
import java.util.Locale

class PaymentCalendarActivity : AppCompatActivity() {
    private enum class CalendarFilter { ALL, REMINDER, RECURRING, UTILITY, OBLIGATION }
    private enum class ViewMode { LIST, MONTH }
    private data class DayCell(val day: Int?, val epochDay: Long?, val entryCount: Int)

    private lateinit var adapter: CalendarAdapter
    private lateinit var monthAdapter: MonthDayAdapter
    private lateinit var budgetManager: BudgetManager
    private lateinit var filterAll: TextView
    private lateinit var filterReminders: TextView
    private lateinit var filterRecurring: TextView
    private lateinit var filterObligations: TextView
    private lateinit var filterUtilities: TextView
    private lateinit var viewListChip: TextView
    private lateinit var viewMonthChip: TextView
    private lateinit var monthNav: View
    private lateinit var monthTitle: TextView
    private lateinit var monthGrid: RecyclerView
    private lateinit var listRecycler: RecyclerView
    private lateinit var emptyView: TextView

    private var allEntries: List<PaymentCalendarHelper.Entry> = emptyList()
    private var currentFilter = CalendarFilter.ALL
    private var viewMode = ViewMode.LIST
    private var visibleMonth: YearMonth = YearMonth.now()
    private var selectedDayEpoch: Long? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_payment_calendar)
        ScreenHeaderHelper.setup(
            this,
            getString(R.string.upcoming_payments_title),
            getString(R.string.main_icon_reminders),
        )
        budgetManager = BudgetManager.getInstance(this)
        filterAll = findViewById(R.id.paymentFilterAll)
        filterReminders = findViewById(R.id.paymentFilterReminders)
        filterRecurring = findViewById(R.id.paymentFilterRecurring)
        filterObligations = findViewById(R.id.paymentFilterObligations)
        filterUtilities = findViewById(R.id.paymentFilterUtilities)
        viewListChip = findViewById(R.id.paymentViewList)
        viewMonthChip = findViewById(R.id.paymentViewMonth)
        monthNav = findViewById(R.id.paymentMonthNav)
        monthTitle = findViewById(R.id.paymentMonthTitle)
        monthGrid = findViewById(R.id.paymentMonthGrid)
        listRecycler = findViewById(R.id.paymentCalendarRecycler)
        emptyView = findViewById(R.id.paymentCalendarEmpty)
        adapter = CalendarAdapter { showEntryActions(it) }
        monthAdapter = MonthDayAdapter { onMonthDayClicked(it) }
        listRecycler.layoutManager = LinearLayoutManager(this)
        listRecycler.adapter = adapter
        monthGrid.layoutManager = GridLayoutManager(this, 7)
        monthGrid.adapter = monthAdapter
        setupFilters()
        setupViewMode()
        findViewById<TextView>(R.id.paymentMonthPrev).setOnClickListener {
            visibleMonth = visibleMonth.minusMonths(1)
            refreshMonthGrid()
        }
        findViewById<TextView>(R.id.paymentMonthNext).setOnClickListener {
            visibleMonth = visibleMonth.plusMonths(1)
            refreshMonthGrid()
        }
    }

    override fun onResume() {
        super.onResume()
        if (::budgetManager.isInitialized) loadEntries()
    }

    private fun setupViewMode() {
        viewListChip.setOnClickListener { setViewMode(ViewMode.LIST) }
        viewMonthChip.setOnClickListener { setViewMode(ViewMode.MONTH) }
        setViewMode(ViewMode.LIST)
    }

    private fun setViewMode(mode: ViewMode) {
        viewMode = mode
        viewListChip.isSelected = mode == ViewMode.LIST
        viewMonthChip.isSelected = mode == ViewMode.MONTH
        val monthVisible = mode == ViewMode.MONTH
        monthNav.visibility = if (monthVisible) View.VISIBLE else View.GONE
        monthGrid.visibility = if (monthVisible) View.VISIBLE else View.GONE
        publishFiltered()
    }

    private fun setupFilters() {
        filterAll.setOnClickListener { applyFilter(CalendarFilter.ALL) }
        filterReminders.setOnClickListener { applyFilter(CalendarFilter.REMINDER) }
        filterRecurring.setOnClickListener { applyFilter(CalendarFilter.RECURRING) }
        filterObligations.setOnClickListener { applyFilter(CalendarFilter.OBLIGATION) }
        filterUtilities.setOnClickListener { applyFilter(CalendarFilter.UTILITY) }
        updateFilterSelection()
    }

    private fun applyFilter(filter: CalendarFilter) {
        currentFilter = filter
        selectedDayEpoch = null
        updateFilterSelection()
        publishFiltered()
    }

    private fun updateFilterSelection() {
        listOf(
            filterAll to CalendarFilter.ALL,
            filterReminders to CalendarFilter.REMINDER,
            filterRecurring to CalendarFilter.RECURRING,
            filterObligations to CalendarFilter.OBLIGATION,
            filterUtilities to CalendarFilter.UTILITY,
        ).forEach { (view, filter) ->
            view.isSelected = filter == currentFilter
        }
    }

    private fun filteredEntries(): List<PaymentCalendarHelper.Entry> {
        val byType = when (currentFilter) {
            CalendarFilter.ALL -> allEntries
            CalendarFilter.REMINDER -> allEntries.filter { it.kind == PaymentCalendarHelper.EntryKind.REMINDER }
            CalendarFilter.RECURRING -> allEntries.filter { it.kind == PaymentCalendarHelper.EntryKind.RECURRING }
            CalendarFilter.UTILITY -> allEntries.filter { it.kind == PaymentCalendarHelper.EntryKind.UTILITY }
            CalendarFilter.OBLIGATION -> allEntries.filter { it.kind == PaymentCalendarHelper.EntryKind.OBLIGATION }
        }
        val day = selectedDayEpoch ?: return byType
        return byType.filter { it.epochDay == day }
    }

    private fun publishFiltered() {
        val filtered = filteredEntries()
        adapter.submit(filtered)
        refreshMonthGrid()
        val showList = viewMode == ViewMode.LIST
        emptyView.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
        listRecycler.visibility = if (showList && filtered.isNotEmpty()) View.VISIBLE else View.GONE
    }

    private fun refreshMonthGrid() {
        if (viewMode != ViewMode.MONTH) return
        val locale = Locale("ru")
        var monthName = visibleMonth.month.getDisplayName(TextStyle.FULL_STANDALONE, locale)
        if (monthName.isNotEmpty()) {
            monthName = monthName.replaceFirstChar { ch ->
                if (ch.isLowerCase()) ch.titlecase(locale) else ch.toString()
            }
        }
        monthTitle.text = "$monthName ${visibleMonth.year}"
        val counts = filteredEntries()
            .filter { YearMonth.from(LocalDate.ofEpochDay(it.epochDay)) == visibleMonth }
            .groupingBy { it.epochDay }
            .eachCount()
        monthAdapter.submit(buildMonthCells(visibleMonth, counts))
    }

    private fun buildMonthCells(month: YearMonth, counts: Map<Long, Int>): List<DayCell> {
        val first = month.atDay(1)
        val offset = (first.dayOfWeek.value + 6) % 7
        val cells = mutableListOf<DayCell>()
        repeat(offset) { cells += DayCell(null, null, 0) }
        for (day in 1..month.lengthOfMonth()) {
            val epoch = month.atDay(day).toEpochDay()
            cells += DayCell(day, epoch, counts[epoch] ?: 0)
        }
        return cells
    }

    private fun onMonthDayClicked(day: DayCell) {
        val epoch = day.epochDay ?: return
        selectedDayEpoch = if (selectedDayEpoch == epoch) null else epoch
        refreshMonthGrid()
        val dayEntries = filteredEntries().filter { it.epochDay == epoch }
        when {
            dayEntries.size == 1 -> showEntryActions(dayEntries.first())
            dayEntries.size > 1 -> setViewMode(ViewMode.LIST)
            else -> publishFiltered()
        }
    }

    private fun showEntryActions(entry: PaymentCalendarHelper.Entry) {
        val labels = mutableListOf<String>()
        val handlers = mutableListOf<() -> Unit>()
        when (entry.kind) {
            PaymentCalendarHelper.EntryKind.REMINDER -> {
                labels += getString(R.string.payment_calendar_action_pay)
                handlers += { payReminder(entry) }
                labels += getString(R.string.payment_calendar_action_open)
                handlers += { startActivity(Intent(this, RemindersActivity::class.java)) }
            }
            PaymentCalendarHelper.EntryKind.RECURRING -> {
                labels += getString(R.string.payment_calendar_action_open)
                handlers += { startActivity(Intent(this, RecurringActivity::class.java)) }
            }
            PaymentCalendarHelper.EntryKind.UTILITY -> {
                labels += getString(R.string.payment_calendar_action_pay)
                handlers += { openUtilityBill(entry, true) }
                labels += getString(R.string.payment_calendar_action_open)
                handlers += { openUtilityBill(entry, false) }
            }
            PaymentCalendarHelper.EntryKind.OBLIGATION -> {
                labels += getString(R.string.payment_calendar_action_pay)
                handlers += { payObligation(entry) }
                labels += getString(R.string.payment_calendar_action_open)
                handlers += { startActivity(Intent(this, PlanFactActivity::class.java)) }
            }
        }
        val message = buildString {
            append(entry.dateLabel)
            val amount = entry.amount
            if (amount != null && amount > 0.0) {
                append(" · ")
                append(MoneyFormat.formatRub(amount))
            }
            if (entry.subtitle.isNotBlank()) {
                append('\n')
                append(entry.subtitle)
            }
        }
        AlertDialog.Builder(this)
            .setTitle(entry.title)
            .setMessage(message)
            .setItems(labels.toTypedArray()) { _, which -> handlers[which]() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun payReminder(entry: PaymentCalendarHelper.Entry) {
        val id = entry.sourceRef.reminderId ?: return
        AlertDialog.Builder(this)
            .setTitle(R.string.reminder_mark_paid_title)
            .setMessage(
                getString(
                    R.string.reminder_mark_paid_msg,
                    entry.title,
                    MoneyFormat.format(entry.amount ?: 0.0),
                ),
            )
            .setPositiveButton(R.string.reminder_mark_paid) { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    val ok = ReminderPaymentHelper.payReminder(budgetManager, id)
                    withContext(Dispatchers.Main) {
                        if (ok) {
                            Toast.makeText(this@PaymentCalendarActivity, R.string.payment_calendar_paid_done, Toast.LENGTH_SHORT).show()
                            loadEntries()
                        }
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun payObligation(entry: PaymentCalendarHelper.Entry) {
        val id = entry.sourceRef.obligationId ?: return
        val amount = entry.amount ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            val ob = budgetManager.repository.getPlannedObligationById(id) ?: return@launch
            budgetManager.recordTransaction(ob.categoryId, amount, "expense", ob.name)
            withContext(Dispatchers.Main) {
                Toast.makeText(this@PaymentCalendarActivity, R.string.payment_calendar_paid_done, Toast.LENGTH_SHORT).show()
                loadEntries()
            }
        }
    }

    private fun openUtilityBill(entry: PaymentCalendarHelper.Entry, openPay: Boolean) {
        val billId = entry.sourceRef.billId ?: return
        startActivity(
            Intent(this, UtilityBillActivity::class.java)
                .putExtra(UtilitiesActivity.EXTRA_BILL_ID, billId)
                .putExtra(UtilityBillActivity.EXTRA_OPEN_PAY, openPay),
        )
    }

    private fun loadEntries() {
        lifecycleScope.launch(Dispatchers.IO) {
            val db = BudgetDatabase.getInstance(this@PaymentCalendarActivity)
            val dao = db.budgetDao()
            val categories = budgetManager.getCategoriesAsync()
            val categoryNames = categories.associate { it.id to it.name }
            val fmt = java.text.SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            val today = LocalDate.now()
            val todayStr = fmt.format(Calendar.getInstance().time)
            val endCal = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, 60) }
            val endStr = fmt.format(endCal.time)
            val reminders = dao.getRemindersInRange(todayStr, endStr)
            val recurring = dao.getRecurringInRange(todayStr, endStr)
            val totals = db.utilityDao().getBillGrandTotals().associate { it.billId to it.total }
            val unpaid = db.utilityDao().getAllBills().mapNotNull { bill ->
                val total = totals[bill.id] ?: 0.0
                if (bill.budgetPaidAt == null && total > 0.0) bill to total else null
            }
            val obligations = dao.getPlannedObligationsByBudgetOnce(budgetManager.getActiveBudgetId())
            val entries = PaymentCalendarHelper.buildEntries(
                reminders,
                recurring,
                unpaid,
                obligations,
                categoryNames,
                today.toEpochDay(),
            )
            withContext(Dispatchers.Main) {
                allEntries = entries
                publishFiltered()
            }
        }
    }

    private class CalendarAdapter(
        private val onClick: (PaymentCalendarHelper.Entry) -> Unit,
    ) : RecyclerView.Adapter<CalendarAdapter.Holder>() {
        private var items: List<PaymentCalendarHelper.Entry> = emptyList()

        class Holder(v: View) : RecyclerView.ViewHolder(v) {
            val date: TextView = v.findViewById(R.id.paymentCalendarDate)
            val title: TextView = v.findViewById(R.id.paymentCalendarTitle)
            val subtitle: TextView = v.findViewById(R.id.paymentCalendarSubtitle)
            val amount: TextView = v.findViewById(R.id.paymentCalendarAmount)
        }

        fun submit(list: List<PaymentCalendarHelper.Entry>) {
            items = list
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_payment_calendar, parent, false)
            return Holder(v)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = items[position]
            holder.date.text = item.dateLabel
            holder.title.text = item.title
            holder.subtitle.text = item.subtitle
            val amount = item.amount
            if (amount != null && amount > 0.0) {
                holder.amount.visibility = View.VISIBLE
                holder.amount.text = MoneyFormat.formatRub(amount)
            } else {
                holder.amount.visibility = View.GONE
            }
            val color = when (item.kind) {
                PaymentCalendarHelper.EntryKind.REMINDER -> R.color.reminders_teal
                PaymentCalendarHelper.EntryKind.RECURRING -> R.color.transactions_purple
                PaymentCalendarHelper.EntryKind.UTILITY -> R.color.utilities_cyan
                PaymentCalendarHelper.EntryKind.OBLIGATION -> R.color.obligations_indigo
            }
            holder.date.setTextColor(ContextCompat.getColor(holder.itemView.context, color))
            holder.itemView.setOnClickListener { onClick(item) }
        }

        override fun getItemCount(): Int = items.size
    }

    private class MonthDayAdapter(
        private val onDayClick: (DayCell) -> Unit,
    ) : RecyclerView.Adapter<MonthDayAdapter.DayHolder>() {
        private var cells: List<DayCell> = emptyList()

        class DayHolder(v: View) : RecyclerView.ViewHolder(v) {
            val number: TextView = v.findViewById(R.id.paymentCalendarDayNumber)
            val dot: View = v.findViewById(R.id.paymentCalendarDayDot)
        }

        fun submit(list: List<DayCell>) {
            cells = list
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DayHolder {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_payment_calendar_day, parent, false)
            return DayHolder(v)
        }

        override fun onBindViewHolder(holder: DayHolder, position: Int) {
            val cell = cells[position]
            if (cell.day == null) {
                holder.itemView.visibility = View.INVISIBLE
                holder.itemView.isClickable = false
                return
            }
            holder.itemView.visibility = View.VISIBLE
            holder.itemView.isClickable = true
            holder.number.text = cell.day.toString()
            holder.dot.visibility = if (cell.entryCount > 0) View.VISIBLE else View.GONE
            holder.itemView.isSelected = cell.entryCount > 0
            holder.itemView.setOnClickListener { onDayClick(cell) }
        }

        override fun getItemCount(): Int = cells.size
    }
}
