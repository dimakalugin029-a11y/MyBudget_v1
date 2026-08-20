package ru.mybudget.app

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import ru.mybudget.app.data.TransactionEntity
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class TransactionsActivity : AppCompatActivity() {
    private enum class TypeFilter { ALL, INCOME, EXPENSE }
    private enum class TimeFilter { MONTH, WEEK, TODAY, ALL }

    private lateinit var manager: BudgetManager
    private lateinit var recycler: RecyclerView
    private lateinit var summaryText: TextView
    private lateinit var emptyState: LinearLayout
    private lateinit var emptyText: TextView
    private lateinit var periodChip: MaterialButton
    private lateinit var searchPanel: View
    private lateinit var searchEdit: EditText
    private lateinit var adapter: TransactionsAdapter
    private var typeFilter = TypeFilter.ALL
    private var timeFilter = TimeFilter.MONTH
    private var searchQuery = ""
    private var filterCategoryIds: IntArray? = null
    private var allTransactions: List<TransactionEntity> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_transactions)
        manager = BudgetManager.getInstance(this)
        filterCategoryIds = intent.getIntArrayExtra(EXTRA_CATEGORY_IDS)
        val title = intent.getStringExtra(EXTRA_CATEGORY_TITLE)?.let {
            getString(R.string.budget_category_history, it)
        } ?: getString(R.string.transactions_title)
        ScreenHeaderHelper.setup(this, title)
        findViewById<View>(R.id.transactionsHint)?.let {
            ScreenHintHelper.bind(
                this,
                it,
                ScreenHintHelper.Keys.TRANSACTIONS,
                R.string.hint_transactions,
                showHelpLink = false,
            )
        }
        ScreenHeaderHelper.bindSecondaryAction(
            this,
            android.R.drawable.ic_menu_search,
            R.string.transactions_search_submit,
        ) {
            searchPanel.visibility = if (searchPanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }

        recycler = findViewById(R.id.transactionsRecyclerView)
        summaryText = findViewById(R.id.summaryText)
        emptyState = findViewById(R.id.transactionsEmptyState)
        emptyText = findViewById(R.id.transactionsEmptyText)
        periodChip = findViewById(R.id.periodFilterChip)
        searchPanel = findViewById(R.id.searchPanel)
        searchEdit = findViewById(R.id.searchEditText)
        adapter = TransactionsAdapter()
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter

        val tabs = findViewById<TabLayout>(R.id.typeTabLayout)
        tabs.addTab(tabs.newTab().setText(R.string.transactions_tab_all))
        tabs.addTab(tabs.newTab().setText(R.string.transactions_tab_income))
        tabs.addTab(tabs.newTab().setText(R.string.transactions_tab_expense))
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                typeFilter = when (tab.position) {
                    1 -> TypeFilter.INCOME
                    2 -> TypeFilter.EXPENSE
                    else -> TypeFilter.ALL
                }
                render()
            }
            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })

        periodChip.setOnClickListener { showPeriodPicker() }
        findViewById<View>(R.id.resetFiltersButton).setOnClickListener {
            typeFilter = TypeFilter.ALL
            timeFilter = TimeFilter.MONTH
            searchQuery = ""
            searchEdit.text.clear()
            tabs.getTabAt(0)?.select()
            updatePeriodChip()
            render()
        }
        searchEdit.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                searchQuery = s?.toString().orEmpty()
                findViewById<View>(R.id.searchClearButton).visibility =
                    if (searchQuery.isBlank()) View.GONE else View.VISIBLE
                render()
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        })
        findViewById<View>(R.id.searchClearButton).setOnClickListener { searchEdit.text.clear() }
        updatePeriodChip()

        lifecycleScope.launch {
            manager.getCategoriesAsync()
            manager.repository.getAllTransactions().collectLatest { list ->
                allTransactions = list
                render()
            }
        }
    }

    private fun showPeriodPicker() {
        val labels = arrayOf(
            getString(R.string.transactions_filter_month),
            getString(R.string.transactions_filter_week),
            getString(R.string.transactions_filter_today),
            getString(R.string.transactions_filter_all),
        )
        AlertDialog.Builder(this)
            .setItems(labels) { _, which ->
                timeFilter = TimeFilter.entries[which]
                updatePeriodChip()
                render()
            }
            .show()
    }

    private fun updatePeriodChip() {
        periodChip.setText(
            when (timeFilter) {
                TimeFilter.MONTH -> R.string.transactions_filter_month
                TimeFilter.WEEK -> R.string.transactions_filter_week
                TimeFilter.TODAY -> R.string.transactions_filter_today
                TimeFilter.ALL -> R.string.transactions_filter_all
            },
        )
    }

    private fun render() {
        val names = manager.getCategories().associate { it.id to it.name }
        val budgetIds = manager.getCategoryIdsForBudget()
        val categoryFilter = filterCategoryIds?.toSet()
        val filtered = allTransactions.filter { tx ->
            val inBudget = categoryFilter?.contains(tx.categoryId) ?: budgetIds.contains(tx.categoryId)
            if (!inBudget) return@filter false
            val typeOk = when (typeFilter) {
                TypeFilter.ALL -> true
                TypeFilter.INCOME -> tx.type == "income"
                TypeFilter.EXPENSE -> tx.type != "income"
            }
            if (!typeOk) return@filter false
            if (!matchesPeriod(tx.date)) return@filter false
            if (searchQuery.isNotBlank()) {
                val haystack = "${tx.description} ${names[tx.categoryId].orEmpty()}"
                if (!haystack.contains(searchQuery, ignoreCase = true)) return@filter false
            }
            true
        }
        adapter.submit(filtered, names)
        val income = filtered.filter { it.type == "income" }.sumOf { it.amount }
        val expense = filtered.filter { it.type != "income" }.sumOf { it.amount }
        summaryText.text = getString(
            R.string.transactions_summary_line,
            MoneyFormat.formatRub(income),
            MoneyFormat.formatRub(expense),
            MoneyFormat.formatRub(income - expense),
        )
        val empty = filtered.isEmpty()
        recycler.visibility = if (empty) View.GONE else View.VISIBLE
        emptyState.visibility = if (empty) View.VISIBLE else View.GONE
        emptyText.setText(R.string.transactions_empty_filter)
    }

    private fun matchesPeriod(date: Long): Boolean {
        if (timeFilter == TimeFilter.ALL) return true
        val start = Calendar.getInstance().apply {
            when (timeFilter) {
                TimeFilter.TODAY -> {
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                TimeFilter.WEEK -> {
                    firstDayOfWeek = Calendar.MONDAY
                    set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                TimeFilter.MONTH -> {
                    set(Calendar.DAY_OF_MONTH, 1)
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                TimeFilter.ALL -> Unit
            }
        }.timeInMillis
        return date >= start
    }

    private fun confirmCancel(transaction: TransactionEntity) {
        val categoryName = manager.getCategories().firstOrNull { it.id == transaction.categoryId }?.name.orEmpty()
        AlertDialog.Builder(this)
            .setTitle(R.string.transaction_cancel_title)
            .setMessage(
                getString(
                    R.string.transaction_cancel_message,
                    categoryName,
                    MoneyFormat.format(transaction.amount),
                ),
            )
            .setPositiveButton(R.string.transaction_cancel_confirm) { _, _ ->
                lifecycleScope.launch {
                    manager.repository.cancelTransaction(transaction)
                    manager.reloadCategoriesFromDatabase()
                    Toast.makeText(this@TransactionsActivity, R.string.transaction_cancelled, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private inner class TransactionsAdapter : RecyclerView.Adapter<TransactionsAdapter.Holder>() {
        private var items: List<TransactionEntity> = emptyList()
        private var names: Map<Int, String> = emptyMap()
        private val dateFormat = SimpleDateFormat("dd.MM.yyyy", Locale("ru", "RU"))

        fun submit(newItems: List<TransactionEntity>, categoryNames: Map<Int, String>) {
            items = newItems
            names = categoryNames
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_transaction, parent, false)
            return Holder(view)
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val tx = items[position]
            holder.category.text = names[tx.categoryId] ?: getString(R.string.income_distribution_parent_fallback)
            holder.description.text = tx.description
            holder.description.visibility = if (tx.description.isBlank()) View.GONE else View.VISIBLE
            val isIncome = tx.type == "income"
            val sign = if (isIncome) "+" else "−"
            holder.amount.text = "$sign ${MoneyFormat.formatRub(tx.amount)}"
            holder.amount.setTextColor(
                ContextCompat.getColor(
                    holder.itemView.context,
                    if (isIncome) R.color.income_green else R.color.expense_red,
                ),
            )
            holder.date.text = dateFormat.format(Date(tx.date))
            holder.actions.setOnClickListener { showMenu(it, tx) }
            holder.itemView.setOnClickListener { showMenu(holder.actions, tx) }
        }

        private fun showMenu(anchor: View, tx: TransactionEntity) {
            PopupMenu(anchor.context, anchor).apply {
                menu.add(0, 1, 0, R.string.transaction_cancel_confirm)
                setOnMenuItemClickListener {
                    confirmCancel(tx)
                    true
                }
                show()
            }
        }

        inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val category: TextView = view.findViewById(R.id.transactionCategory)
            val description: TextView = view.findViewById(R.id.transactionDescription)
            val amount: TextView = view.findViewById(R.id.transactionAmount)
            val date: TextView = view.findViewById(R.id.transactionDate)
            val actions: ImageButton = view.findViewById(R.id.transactionActionsButton)
        }
    }

    companion object {
        const val EXTRA_CATEGORY_IDS = "filter_category_ids"
        const val EXTRA_CATEGORY_TITLE = "filter_category_title"
    }
}
