package ru.mybudget.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.mybudget.app.data.AuditActionEntity
import ru.mybudget.app.data.AuditActionType
import ru.mybudget.app.data.TransactionEntity
import ru.mybudget.app.imports.CsvTransactionImporter
import ru.mybudget.app.setup.ImportCategoryMappingPreferences
import ru.mybudget.app.setup.ParticipantPreferences
import ru.mybudget.app.transactions.TransactionDayNetHelper
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
    private var filteredTransactions: List<TransactionEntity> = emptyList()
    private var auditActions: List<AuditActionEntity> = emptyList()

    private val exportCsvLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv"),
    ) { uri -> if (uri != null) writeCsv(uri) }

    private val importCsvLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> if (uri != null) readCsv(uri) }

    private var pendingOpenImport = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_transactions)
        manager = BudgetManager.getInstance(this)
        filterCategoryIds = intent.getIntArrayExtra(EXTRA_CATEGORY_IDS)
        pendingOpenImport = intent.getBooleanExtra(EXTRA_OPEN_IMPORT, false)
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
        ScreenHeaderHelper.bindAction(
            this,
            android.R.drawable.ic_menu_more,
            R.string.transactions_more_menu,
        ) {
            PopupMenu(this, findViewById(R.id.screenHeaderAction)).apply {
                menu.add(0, 1, 0, R.string.transactions_export_csv)
                menu.add(0, 2, 1, R.string.transactions_import_csv)
                setOnMenuItemClickListener { item ->
                    when (item.itemId) {
                        1 -> {
                            val date = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
                            exportCsvLauncher.launch("mybudget_history_$date.csv")
                        }
                        2 -> importCsvLauncher.launch(
                            arrayOf("text/csv", "text/comma-separated-values", "*/*"),
                        )
                    }
                    true
                }
                show()
            }
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
            loadAuditActions()
            manager.repository.getAllTransactions().collectLatest { list ->
                allTransactions = list
                render()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        loadAuditActions()
        if (pendingOpenImport) {
            pendingOpenImport = false
            importCsvLauncher.launch(arrayOf("text/*", "text/csv", "application/vnd.ms-excel", "*/*"))
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
        filteredTransactions = filtered
        val includeAudit = filterCategoryIds == null &&
            typeFilter == TypeFilter.ALL &&
            searchQuery.isBlank()
        adapter.submit(filtered, names, if (includeAudit) auditActions else emptyList(), includeAudit)
        val income = filtered.filter { it.type == "income" }.sumOf { it.amount }
        val expense = filtered.filter { it.type != "income" }.sumOf { it.amount }
        summaryText.text = getString(
            R.string.transactions_summary_line,
            MoneyFormat.formatRub(income),
            MoneyFormat.formatRub(expense),
            MoneyFormat.formatRub(income - expense),
        )
        val empty = filtered.isEmpty() && !(includeAudit && auditActions.isNotEmpty())
        recycler.visibility = if (empty) View.GONE else View.VISIBLE
        emptyState.visibility = if (empty) View.VISIBLE else View.GONE
        emptyText.setText(R.string.transactions_empty_filter)
    }

    private fun loadAuditActions() {
        lifecycleScope.launch(Dispatchers.IO) {
            val actions = manager.repository.getActiveAuditActions()
            withContext(Dispatchers.Main) {
                auditActions = actions
                render()
            }
        }
    }

    private fun undoAuditAction(action: AuditActionEntity) {
        if (action.actionType != AuditActionType.CATEGORY_DELETED) return
        AlertDialog.Builder(this)
            .setTitle(action.title)
            .setMessage(action.description)
            .setPositiveButton(R.string.audit_action_undo) { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    val ok = AuditLogHelper.undoCategoryDeleted(manager.repository, manager, action)
                    withContext(Dispatchers.Main) {
                        if (ok) {
                            BudgetWidgetProvider.updateAll(this@TransactionsActivity)
                            Toast.makeText(
                                this@TransactionsActivity,
                                R.string.audit_action_undone,
                                Toast.LENGTH_SHORT,
                            ).show()
                            loadAuditActions()
                        }
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
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

    private fun writeCsv(uri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val csv = buildExportCsv(filteredTransactions)
                contentResolver.openOutputStream(uri)?.use { stream ->
                    stream.write(csv.toByteArray(Charsets.UTF_8))
                } ?: error("no stream")
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@TransactionsActivity, R.string.transactions_export_done, Toast.LENGTH_SHORT).show()
                }
            } catch (_: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@TransactionsActivity, R.string.transactions_export_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private suspend fun buildExportCsv(transactions: List<TransactionEntity>): String {
        val categories = manager.getCategoriesAsync()
        val names = categories.associate { it.id to it.name }
        val budgetIds = categories.associate { it.id to it.budgetId }
        val budgetNames = manager.getBudgetProfilesAsync().associate { it.id to it.name }
        val df = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        val sb = StringBuilder("date,budget,category,type,amount,description\n")
        transactions.forEach { tx ->
            val date = df.format(Date(tx.date))
            val budget = budgetNames[budgetIds[tx.categoryId]].orEmpty().replace(",", " ")
            val category = names[tx.categoryId].orEmpty().replace(",", " ")
            val amount = String.format(Locale.getDefault(), "%.2f", tx.amount)
            val desc = tx.description.replace(",", " ")
            sb.append("$date,$budget,$category,${tx.type},$amount,$desc\n")
        }
        return sb.toString()
    }

    private fun readCsv(uri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val text = contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                    ?: error("no stream")
                val parsed = CsvTransactionImporter.parse(text)
                if (parsed.rows.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@TransactionsActivity, R.string.transactions_import_failed, Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }
                val all = manager.getCategoriesAsync()
                val parents = all.associate { it.id to it.name }
                val leaf = all.filter { it.isActive && !manager.hasSubcategories(it.id) }
                if (leaf.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@TransactionsActivity, R.string.utility_pay_no_categories, Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }
                val labels = leaf.associate { it.id to CategoryMultiPicker.leafLabel(it, parents) }
                val budgetId = manager.getActiveBudgetId()
                val rules = ImportCategoryMappingPreferences.getRules(this@TransactionsActivity, budgetId)
                val resolvedCount = parsed.rows.count { row ->
                    CsvTransactionImporter.resolveCategoryId(row.categoryName, labels, row.description, rules) != null
                }
                val needsDefault = resolvedCount < parsed.rows.size
                withContext(Dispatchers.Main) {
                    confirmImport(parsed, labels, rules, leaf, parents, needsDefault, budgetId)
                }
            } catch (_: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@TransactionsActivity, R.string.transactions_import_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun confirmImport(
        parsed: CsvTransactionImporter.ParseResult,
        labels: Map<Int, String>,
        rules: List<ImportCategoryMappingPreferences.Rule>,
        leaf: List<BudgetCategory>,
        parents: Map<Int, String>,
        needsDefault: Boolean,
        budgetId: Int,
    ) {
        val message = buildString {
            append(getString(R.string.transactions_import_preview, parsed.rows.size))
            if (parsed.skipped > 0) {
                append("\n")
                append(getString(R.string.transactions_import_preview_skipped, parsed.skipped))
            }
            if (needsDefault) {
                append("\n")
                append(getString(R.string.transactions_import_preview_need_category))
            }
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.transactions_import_csv)
            .setMessage(message)
            .setPositiveButton(R.string.transactions_import_confirm) { _, _ ->
                if (needsDefault) {
                    pickDefaultCategoryAndImport(parsed.rows, leaf, labels, parents, rules, budgetId)
                } else {
                    importRows(parsed.rows, labels, rules, null, budgetId)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun pickDefaultCategoryAndImport(
        rows: List<CsvTransactionImporter.ParsedRow>,
        leaf: List<BudgetCategory>,
        labels: Map<Int, String>,
        parents: Map<Int, String>,
        rules: List<ImportCategoryMappingPreferences.Rule>,
        budgetId: Int,
    ) {
        val options = leaf.map { CategoryMultiPicker.leafLabel(it, parents) }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.transactions_import_pick_category)
            .setItems(options) { _, which ->
                importRows(rows, labels, rules, leaf[which].id, budgetId)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun importRows(
        rows: List<CsvTransactionImporter.ParsedRow>,
        labels: Map<Int, String>,
        rules: List<ImportCategoryMappingPreferences.Rule>,
        defaultCategoryId: Int?,
        budgetId: Int,
    ) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                var imported = 0
                val activeRules = rules.toMutableList()
                for (row in rows) {
                    val categoryId = CsvTransactionImporter.resolveCategoryId(
                        row.categoryName,
                        labels,
                        row.description,
                        activeRules,
                    ) ?: defaultCategoryId ?: continue
                    val description = row.description.ifBlank { row.categoryName }
                    manager.repository.recordTransaction(
                        categoryId = categoryId,
                        amount = MoneyFormat.roundMoney(row.amount),
                        type = row.type,
                        description = description,
                        date = row.dateMillis,
                    )
                    if (
                        defaultCategoryId != null &&
                        categoryId == defaultCategoryId &&
                        row.description.isNotBlank() &&
                        CsvTransactionImporter.resolveCategoryId(row.categoryName, labels, row.description, rules) == null
                    ) {
                        ImportCategoryMappingPreferences.remember(
                            this@TransactionsActivity,
                            budgetId,
                            row.description,
                            categoryId,
                        )
                        ImportCategoryMappingPreferences.ruleFor(row.description, categoryId)
                            ?.let { activeRules.add(0, it) }
                    }
                    imported++
                }
                manager.reloadCategoriesFromDatabase()
                withContext(Dispatchers.Main) {
                    BudgetWidgetProvider.updateAll(this@TransactionsActivity)
                    Toast.makeText(
                        this@TransactionsActivity,
                        getString(R.string.transactions_import_done, imported),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            } catch (_: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@TransactionsActivity, R.string.transactions_import_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun showEditDialog(transaction: TransactionEntity) {
        lifecycleScope.launch(Dispatchers.IO) {
            val all = manager.getCategoriesAsync()
            val txBudgetId = all.firstOrNull { it.id == transaction.categoryId }?.budgetId
            val parents = all.associate { it.id to it.name }
            val options = all.filter { category ->
                category.isActive &&
                    !manager.hasSubcategories(category.id) &&
                    (txBudgetId == null || category.budgetId == txBudgetId)
            }.map { it to CategoryMultiPicker.leafLabel(it, parents) }
            withContext(Dispatchers.Main) {
                if (options.isEmpty()) {
                    Toast.makeText(this@TransactionsActivity, R.string.utility_pay_no_categories, Toast.LENGTH_SHORT).show()
                } else {
                    openEditDialog(transaction, options)
                }
            }
        }
    }

    private fun openEditDialog(transaction: TransactionEntity, options: List<Pair<BudgetCategory, String>>) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_edit_transaction, null)
        val categorySpinner = dialogView.findViewById<Spinner>(R.id.editCategorySpinner)
        val amountInput = dialogView.findViewById<EditText>(R.id.editAmountInput)
        val descriptionInput = dialogView.findViewById<EditText>(R.id.editDescriptionInput)
        val participantSpinner = dialogView.findViewById<Spinner>(R.id.editParticipantSpinner)
        categorySpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            options.map { it.second },
        )
        categorySpinner.setSelection(options.indexOfFirst { it.first.id == transaction.categoryId }.coerceAtLeast(0))
        val participants = listOf(getString(R.string.transaction_edit_participant_hint)) +
            ParticipantPreferences.getNames(this)
        participantSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            participants,
        )
        participantSpinner.setSelection(participants.indexOf(transaction.participantLabel).coerceAtLeast(0))
        amountInput.setText(MoneyFormat.format(transaction.amount))
        descriptionInput.setText(transaction.description)
        AlertDialog.Builder(this)
            .setTitle(R.string.transaction_edit_title)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val newAmount = MoneyFormat.parse(amountInput.text)
                if (newAmount == null || newAmount <= 0.0) {
                    Toast.makeText(this, R.string.error_invalid_amount, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val newCategory = options[categorySpinner.selectedItemPosition].first
                val selectedParticipant = participantSpinner.selectedItem?.toString().orEmpty()
                val participantLabel = if (selectedParticipant == getString(R.string.transaction_edit_participant_hint)) {
                    ""
                } else {
                    selectedParticipant
                }
                val newDescription = descriptionInput.text.toString().trim().ifBlank { transaction.description }
                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        manager.repository.correctTransaction(
                            original = transaction,
                            newCategoryId = newCategory.id,
                            newAmount = newAmount,
                            newType = transaction.type,
                            newDescription = newDescription,
                            newParticipantLabel = participantLabel,
                        )
                        manager.reloadCategoriesFromDatabase()
                        withContext(Dispatchers.Main) {
                            BudgetWidgetProvider.updateAll(this@TransactionsActivity)
                            Toast.makeText(this@TransactionsActivity, R.string.transaction_updated, Toast.LENGTH_SHORT).show()
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(
                                this@TransactionsActivity,
                                getString(R.string.error_generic, e.message.orEmpty()),
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .showWithIme(dialogView, arrayOf(amountInput, descriptionInput))
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
                    BudgetWidgetProvider.updateAll(this@TransactionsActivity)
                    Toast.makeText(this@TransactionsActivity, R.string.transaction_cancelled, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showCancelGroupDialog(transactions: List<TransactionEntity>) {
        val groupId = transactions.firstOrNull()?.groupId ?: return
        val total = transactions.sumOf { if (it.type == "income") it.amount else -it.amount }
        val totalLabel = if (total >= 0.0) "+${MoneyFormat.format(total)}" else MoneyFormat.format(total)
        AlertDialog.Builder(this)
            .setTitle(R.string.transaction_group_cancel_title)
            .setMessage(getString(R.string.transaction_group_cancel_message, transactions.size, totalLabel))
            .setPositiveButton(R.string.transaction_group_cancel_all) { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        manager.repository.cancelTransactionGroup(groupId)
                        manager.reloadCategoriesFromDatabase()
                        withContext(Dispatchers.Main) {
                            BudgetWidgetProvider.updateAll(this@TransactionsActivity)
                            Toast.makeText(
                                this@TransactionsActivity,
                                R.string.transaction_group_cancelled,
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(
                                this@TransactionsActivity,
                                getString(R.string.error_generic, e.message.orEmpty()),
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun redistributeGroup(transactions: List<TransactionEntity>) {
        val groupId = transactions.firstOrNull()?.groupId ?: return
        val type = transactions.firstOrNull()?.type ?: "income"
        val total = transactions.sumOf { it.amount }
        val note = transactions.firstOrNull()?.description.orEmpty()
        val categoryIds = transactions.map { it.categoryId }.toIntArray()
        val amounts = transactions.map { it.amount }.toDoubleArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.transaction_group_redistribute)
            .setMessage(getString(R.string.transaction_group_cancel_message, transactions.size, MoneyFormat.format(total)))
            .setPositiveButton(R.string.transaction_group_redistribute) { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        manager.repository.cancelTransactionGroup(groupId)
                        manager.getCategoriesAsync()
                        withContext(Dispatchers.Main) {
                            BudgetWidgetProvider.updateAll(this@TransactionsActivity)
                            if (type == "expense") {
                                startActivity(
                                    Intent(this@TransactionsActivity, ExpenseDistributionActivity::class.java)
                                        .putExtra(ExpenseDistributionActivity.EXTRA_TOTAL_EXPENSE, total)
                                        .putExtra(ExpenseDistributionActivity.EXTRA_EXPENSE_NOTE, note)
                                        .putExtra(ExpenseDistributionActivity.EXTRA_PREFILL_CATEGORY_IDS, categoryIds)
                                        .putExtra(ExpenseDistributionActivity.EXTRA_PREFILL_AMOUNTS, amounts),
                                )
                            } else {
                                startActivity(
                                    Intent(this@TransactionsActivity, IncomeDistributionActivity::class.java)
                                        .putExtra(IncomeDistributionActivity.EXTRA_TOTAL_INCOME, total)
                                        .putExtra(IncomeDistributionActivity.EXTRA_INCOME_NOTE, note)
                                        .putExtra(IncomeDistributionActivity.EXTRA_PREFILL_CATEGORY_IDS, categoryIds)
                                        .putExtra(IncomeDistributionActivity.EXTRA_PREFILL_AMOUNTS, amounts),
                                )
                            }
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(
                                this@TransactionsActivity,
                                getString(R.string.error_generic, e.message.orEmpty()),
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private inner class TransactionsAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private var transactions: List<TransactionEntity> = emptyList()
        private var names: Map<Int, String> = emptyMap()
        private var audits: List<AuditActionEntity> = emptyList()
        private var includeAudit = false
        private var rows: List<HistoryRow> = emptyList()
        private val expandedGroups = linkedSetOf<String>()
        private val dateFormat = SimpleDateFormat("dd.MM HH:mm", Locale.getDefault())
        private val ruLocale = Locale("ru")

        fun submit(
            newItems: List<TransactionEntity>,
            categoryNames: Map<Int, String>,
            newAudits: List<AuditActionEntity> = emptyList(),
            includeAuditRows: Boolean = false,
        ) {
            transactions = newItems
            names = categoryNames
            audits = newAudits
            includeAudit = includeAuditRows
            rows = buildRows(newItems)
            notifyDataSetChanged()
        }

        override fun getItemCount(): Int = rows.size

        override fun getItemViewType(position: Int): Int = when (rows[position]) {
            is HistoryRow.Header -> TYPE_HEADER
            is HistoryRow.Group -> TYPE_GROUP
            is HistoryRow.Audit -> TYPE_AUDIT
            is HistoryRow.Single -> TYPE_TRANSACTION
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return when (viewType) {
                TYPE_HEADER -> HeaderHolder(inflater.inflate(R.layout.item_transaction_header, parent, false))
                TYPE_GROUP -> GroupHolder(inflater.inflate(R.layout.item_transaction_group, parent, false))
                TYPE_AUDIT -> AuditHolder(inflater.inflate(R.layout.item_audit_action, parent, false))
                else -> TxHolder(inflater.inflate(R.layout.item_transaction, parent, false))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val row = rows[position]) {
                is HistoryRow.Header -> bindHeader(holder as HeaderHolder, row)
                is HistoryRow.Group -> bindGroup(holder as GroupHolder, row)
                is HistoryRow.Audit -> bindAudit(holder as AuditHolder, row.action)
                is HistoryRow.Single -> bindTransaction(holder as TxHolder, row.transaction)
            }
        }

        private fun bindHeader(holder: HeaderHolder, row: HistoryRow.Header) {
            holder.title.text = row.title
            val net = row.dayNet
            if (net == null) {
                holder.net.visibility = View.GONE
            } else {
                holder.net.visibility = View.VISIBLE
                holder.net.text = if (net >= 0.0) "+${MoneyFormat.formatRub(net)}" else MoneyFormat.formatRub(net)
                holder.net.setTextColor(
                    ContextCompat.getColor(
                        holder.itemView.context,
                        if (net >= 0.0) R.color.income_green else R.color.expense_red,
                    ),
                )
            }
        }

        private fun bindGroup(holder: GroupHolder, row: HistoryRow.Group) {
            val txs = row.transactions
            val expanded = row.groupId in expandedGroups
            val allExpense = txs.isNotEmpty() && txs.all { it.type != "income" }
            val defaultTitle = holder.itemView.context.getString(
                if (allExpense) R.string.transaction_expense_distribution else R.string.transaction_income_distribution,
            )
            holder.title.text = txs.firstOrNull()?.description?.takeIf { it.isNotBlank() } ?: defaultTitle
            holder.subtitle.text = holder.itemView.context.getString(R.string.transaction_group_subtitle, txs.size)
            val net = TransactionDayNetHelper.groupNet(txs)
            if (net >= 0.0) {
                holder.amount.text = "+${MoneyFormat.formatRub(net)}"
                holder.amount.setTextColor(ContextCompat.getColor(holder.itemView.context, R.color.income_green))
            } else {
                holder.amount.text = "−${MoneyFormat.formatRub(-net)}"
                holder.amount.setTextColor(ContextCompat.getColor(holder.itemView.context, R.color.expense_red))
            }
            holder.expandIcon.text = holder.itemView.context.getString(
                if (expanded) R.string.ui_chevron_down else R.string.ui_chevron_right,
            )
            holder.itemView.setOnClickListener {
                if (expanded) expandedGroups.remove(row.groupId) else expandedGroups.add(row.groupId)
                rows = buildRows(transactions)
                notifyDataSetChanged()
            }
            holder.actions.setOnClickListener { showGroupMenu(it, txs) }
        }

        private fun bindTransaction(holder: TxHolder, tx: TransactionEntity) {
            holder.category.text = names[tx.categoryId] ?: getString(R.string.income_distribution_parent_fallback)
            val desc = buildString {
                if (tx.participantLabel.isNotBlank()) {
                    append(tx.participantLabel)
                    append(" · ")
                }
                append(tx.description.ifBlank { "—" })
            }
            holder.description.text = desc
            holder.description.visibility = if (desc.isBlank() || desc == "—") View.GONE else View.VISIBLE
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
            holder.actions.setOnClickListener { showRowMenu(it, tx) }
            holder.itemView.setOnClickListener { showRowMenu(holder.actions, tx) }
        }

        private fun bindAudit(holder: AuditHolder, action: AuditActionEntity) {
            holder.title.text = action.title
            holder.description.text = action.description
            holder.date.text = dateFormat.format(Date(action.createdAt))
            holder.undo.setOnClickListener { undoAuditAction(action) }
        }

        private fun showRowMenu(anchor: View, tx: TransactionEntity) {
            val groupMembers = tx.groupId
                ?.takeIf { it.isNotBlank() }
                ?.let { id -> transactions.filter { it.groupId == id } }
                .orEmpty()
            PopupMenu(anchor.context, anchor).apply {
                menu.add(0, 1, 1, R.string.action_edit)
                menu.add(
                    0,
                    2,
                    2,
                    if (groupMembers.size > 1) R.string.transaction_group_cancel_line else R.string.action_cancel_transaction,
                )
                if (groupMembers.size > 1) {
                    menu.add(0, 3, 3, R.string.transaction_group_redistribute)
                    menu.add(0, 4, 4, R.string.transaction_group_cancel_all)
                }
                setOnMenuItemClickListener { item ->
                    when (item.itemId) {
                        1 -> showEditDialog(tx)
                        2 -> confirmCancel(tx)
                        3 -> redistributeGroup(groupMembers)
                        4 -> showCancelGroupDialog(groupMembers)
                    }
                    true
                }
                show()
            }
        }

        private fun showGroupMenu(anchor: View, txs: List<TransactionEntity>) {
            PopupMenu(anchor.context, anchor).apply {
                menu.add(0, 1, 1, R.string.transaction_group_redistribute)
                menu.add(0, 2, 2, R.string.transaction_group_cancel_all)
                setOnMenuItemClickListener { item ->
                    when (item.itemId) {
                        1 -> redistributeGroup(txs)
                        else -> showCancelGroupDialog(txs)
                    }
                    true
                }
                show()
            }
        }

        private fun buildRows(input: List<TransactionEntity>): List<HistoryRow> {
            val grouped = input.filter { !it.groupId.isNullOrBlank() }.groupBy { it.groupId.orEmpty() }
            val consumed = mutableSetOf<String>()
            val timeline = mutableListOf<Pair<Long, HistoryRow>>()
            for (tx in input) {
                val groupId = tx.groupId
                if (groupId.isNullOrBlank()) {
                    timeline += tx.date to HistoryRow.Single(tx)
                } else if (consumed.add(groupId)) {
                    val members = grouped[groupId].orEmpty().sortedByDescending { it.date }
                    if (members.isNotEmpty()) {
                        timeline += members.first().date to HistoryRow.Group(groupId, members)
                    }
                }
            }
            if (includeAudit) {
                for (action in audits) {
                    timeline += action.createdAt to HistoryRow.Audit(action)
                }
            }
            timeline.sortByDescending { it.first }
            val remainderLabel = getString(R.string.transaction_remainder_distribution)
            val dailyNets = TransactionDayNetHelper.computeDailyNets(input, remainderLabel, ::dayKey)
            val built = mutableListOf<HistoryRow>()
            var lastHeader: String? = null
            for ((_, item) in timeline) {
                val date = when (item) {
                    is HistoryRow.Single -> item.transaction.date
                    is HistoryRow.Group -> item.transactions.first().date
                    is HistoryRow.Audit -> item.action.createdAt
                    is HistoryRow.Header -> 0L
                }
                val header = dayKey(date)
                if (header != lastHeader) {
                    built += HistoryRow.Header(header, dailyNets[header])
                    lastHeader = header
                }
                built += item
                if (item is HistoryRow.Group && item.groupId in expandedGroups) {
                    built += item.transactions.map { HistoryRow.Single(it) }
                }
            }
            return built
        }

        private fun dayKey(timestamp: Long): String {
            val calendar = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            val todayStart = calendar.timeInMillis
            val yesterdayStart = todayStart - 86_400_000L
            return when {
                timestamp >= todayStart -> getString(R.string.date_header_today)
                timestamp >= yesterdayStart -> getString(R.string.date_header_yesterday)
                else -> {
                    val formatted = SimpleDateFormat(getString(R.string.date_header_format), ruLocale).format(Date(timestamp))
                    formatted.replaceFirstChar { ch ->
                        if (ch.isLowerCase()) ch.titlecase(ruLocale) else ch.toString()
                    }
                }
            }
        }

        inner class HeaderHolder(view: View) : RecyclerView.ViewHolder(view) {
            val title: TextView = view.findViewById(R.id.transactionHeaderText)
            val net: TextView = view.findViewById(R.id.transactionHeaderNet)
        }

        inner class GroupHolder(view: View) : RecyclerView.ViewHolder(view) {
            val title: TextView = view.findViewById(R.id.groupTitle)
            val subtitle: TextView = view.findViewById(R.id.groupSubtitle)
            val amount: TextView = view.findViewById(R.id.groupAmount)
            val expandIcon: TextView = view.findViewById(R.id.groupExpandIcon)
            val actions: ImageButton = view.findViewById(R.id.groupActionsButton)
        }

        inner class TxHolder(view: View) : RecyclerView.ViewHolder(view) {
            val category: TextView = view.findViewById(R.id.transactionCategory)
            val description: TextView = view.findViewById(R.id.transactionDescription)
            val amount: TextView = view.findViewById(R.id.transactionAmount)
            val date: TextView = view.findViewById(R.id.transactionDate)
            val actions: ImageButton = view.findViewById(R.id.transactionActionsButton)
        }

        inner class AuditHolder(view: View) : RecyclerView.ViewHolder(view) {
            val title: TextView = view.findViewById(R.id.auditActionTitle)
            val description: TextView = view.findViewById(R.id.auditActionDescription)
            val date: TextView = view.findViewById(R.id.auditActionDate)
            val undo: MaterialButton = view.findViewById(R.id.auditActionUndoButton)
        }
    }

    companion object {
        const val EXTRA_CATEGORY_IDS = "filter_category_ids"
        const val EXTRA_CATEGORY_TITLE = "filter_category_title"
        const val EXTRA_OPEN_IMPORT = "open_import"
        private const val TYPE_TRANSACTION = 0
        private const val TYPE_HEADER = 1
        private const val TYPE_GROUP = 2
        private const val TYPE_AUDIT = 3
    }
}

private sealed class HistoryRow {
    data class Header(val title: String, val dayNet: Double?) : HistoryRow()
    data class Single(val transaction: TransactionEntity) : HistoryRow()
    data class Group(val groupId: String, val transactions: List<TransactionEntity>) : HistoryRow()
    data class Audit(val action: AuditActionEntity) : HistoryRow()
}
