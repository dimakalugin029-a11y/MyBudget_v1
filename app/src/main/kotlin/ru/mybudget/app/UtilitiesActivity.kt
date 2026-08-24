package ru.mybudget.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.mybudget.app.data.UtilityBillEntity
import ru.mybudget.app.setup.UtilitySetupPreferences
import ru.mybudget.app.utilities.EnrichedUtilityBillSummary
import ru.mybudget.app.utilities.MeterExcelFormat
import ru.mybudget.app.utilities.UtilityAnomalyHelper
import ru.mybudget.app.utilities.UtilityBillSummary
import ru.mybudget.app.utilities.UtilityExcelExporter
import ru.mybudget.app.utilities.UtilityExcelIo
import ru.mybudget.app.utilities.UtilityExcelParser
import ru.mybudget.app.utilities.UtilityForecastHelper
import ru.mybudget.app.utilities.UtilityMonthChecklistHelper
import ru.mybudget.app.utilities.UtilitySetupGuideHelper
import ru.mybudget.app.utilities.UtilitySetupState
import ru.mybudget.app.utilities.UtilityUserTemplate
import java.time.LocalDate
import java.util.Calendar

class UtilitiesActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_BILL_ID = "utility_bill_id"
    }

    private lateinit var manager: BudgetManager
    private lateinit var adapter: MonthAdapter
    private var pendingImportUri: Uri? = null
    private var loadingDialog: AlertDialog? = null

    private val exportXlsxLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument(MeterExcelFormat.XLSX_MIME),
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        showLoading(getString(R.string.settings_export_loading))
        lifecycleScope.launch {
            val ok = UtilityExcelIo.saveCommunal(contentResolver, uri, manager.utilityDao)
            hideLoading()
            Toast.makeText(
                this@UtilitiesActivity,
                if (ok) R.string.export_excel_success else R.string.export_excel_failed,
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    private val importXlsxLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        pendingImportUri = uri
        lifecycleScope.launch {
            val billCount = withContext(Dispatchers.IO) { manager.utilityDao.getBillCount() }
            if (billCount == 0) {
                importCommunal(replace = false)
                return@launch
            }
            AlertDialog.Builder(this@UtilitiesActivity)
                .setTitle(R.string.utility_excel_import_title)
                .setMessage(R.string.utility_excel_import_message)
                .setPositiveButton(R.string.utility_excel_import_replace) { _, _ ->
                    importCommunal(replace = true)
                }
                .setNeutralButton(R.string.utility_excel_import_keep) { _, _ ->
                    importCommunal(replace = false)
                }
                .setNegativeButton(android.R.string.cancel) { _, _ -> pendingImportUri = null }
                .show()
        }
    }
    private val monthNames = arrayOf(
        "Январь", "Февраль", "Март", "Апрель", "Май", "Июнь",
        "Июль", "Август", "Сентябрь", "Октябрь", "Ноябрь", "Декабрь",
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_utilities)
        manager = BudgetManager.getInstance(this)
        ScreenHeaderHelper.setup(this, getString(R.string.main_menu_utilities))
        adapter = MonthAdapter(
            onOpen = { billId ->
                startActivity(
                    Intent(this, UtilityBillActivity::class.java).putExtra(EXTRA_BILL_ID, billId),
                )
            },
            onLongClick = { summary -> confirmDelete(summary) },
        )
        val recycler = findViewById<RecyclerView>(R.id.utilitiesRecyclerView)
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter
        MenuRowHelper.bind(
            findViewById(R.id.utilityPayCategoryButton),
            "🏦",
            getString(R.string.utility_pay_category_setting),
        ) { pickPayCategory() }
        MenuRowHelper.bind(
            findViewById(R.id.utilityTemplateButton),
            "📋",
            getString(R.string.utility_template_button),
        ) { startActivity(Intent(this, UtilityTemplateActivity::class.java)) }
        MenuRowHelper.bind(
            findViewById(R.id.utilityTariffsButton),
            "💲",
            getString(R.string.utility_tariffs_button),
        ) { startActivity(Intent(this, UtilityTariffsActivity::class.java)) }
        MenuRowHelper.bind(
            findViewById(R.id.utilityCompareButton),
            "📈",
            getString(R.string.utility_compare_button),
        ) { startActivity(Intent(this, UtilityCompareActivity::class.java)) }
        MenuRowHelper.bind(
            findViewById(R.id.metersButton),
            "🔢",
            getString(R.string.utility_meters_button),
        ) { startActivity(Intent(this, UtilityMetersActivity::class.java)) }
        MenuRowHelper.bind(
            findViewById(R.id.exportCommunalButton),
            "📤",
            getString(R.string.export_communal_excel),
        ) { exportXlsxLauncher.launch(UtilityExcelExporter.suggestedCommunalFileName()) }
        MenuRowHelper.bind(
            findViewById(R.id.importExcelButton),
            "📥",
            getString(R.string.utility_import_excel),
        ) { importXlsxLauncher.launch(MeterExcelFormat.XLSX_OPEN_MIME_TYPES) }
        findViewById<View>(R.id.addUtilityMonthButton).setOnClickListener { showAddMonthDialog() }
        CollapsibleBottomSheetHelper.attach(
            findViewById(R.id.utilitiesActionsSheet),
            findViewById(R.id.bottomSheetHeader),
            findViewById(R.id.bottomSheetChevron),
            recycler,
            resources.getDimensionPixelSize(R.dimen.space_8),
        )
        if (!UtilitySetupPreferences.hasSeenIntro(this)) {
            showIntroDialog()
        }
    }

    override fun onResume() {
        super.onResume()
        loadMonths()
        refreshPayCategoryRow()
        refreshSetupGuide()
    }

    override fun onDestroy() {
        hideLoading()
        super.onDestroy()
    }

    private fun importCommunal(replace: Boolean) {
        val uri = pendingImportUri ?: return
        pendingImportUri = null
        showLoading(getString(R.string.settings_import_loading))
        lifecycleScope.launch {
            val result = UtilityExcelIo.importCommunal(contentResolver, uri, manager.utilityDao, replace)
            hideLoading()
            result.fold(
                onSuccess = { imported ->
                    if (imported.isEmpty) {
                        Toast.makeText(
                            this@UtilitiesActivity,
                            R.string.utility_excel_import_empty,
                            Toast.LENGTH_LONG,
                        ).show()
                    } else {
                        Toast.makeText(
                            this@UtilitiesActivity,
                            getString(
                                R.string.utility_excel_import_success,
                                imported.monthsImported,
                                imported.meterRowsImported,
                            ),
                            Toast.LENGTH_LONG,
                        ).show()
                        loadMonths()
                    }
                },
                onFailure = {
                    Toast.makeText(this@UtilitiesActivity, R.string.import_excel_failed, Toast.LENGTH_LONG).show()
                },
            )
        }
    }

    private fun showLoading(message: String) {
        hideLoading()
        loadingDialog = AlertDialog.Builder(this)
            .setMessage(message)
            .setCancelable(false)
            .show()
    }

    private fun hideLoading() {
        loadingDialog?.dismiss()
        loadingDialog = null
    }

    private fun refreshSetupGuide() {
        lifecycleScope.launch {
            val state = withContext(Dispatchers.IO) { UtilitySetupState.load(manager.utilityDao) }
            UtilitySetupGuideHelper.bind(
                this@UtilitiesActivity,
                findViewById(R.id.utilitiesSetupGuide),
                state,
            )
        }
    }

    private fun showIntroDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.utility_intro_title)
            .setMessage(R.string.utility_intro_message)
            .setPositiveButton(R.string.utility_intro_open_template) { _, _ ->
                UtilitySetupPreferences.markIntroShown(this)
                startActivity(Intent(this, UtilityTemplateActivity::class.java))
            }
            .setNegativeButton(R.string.utility_intro_got_it) { _, _ ->
                UtilitySetupPreferences.markIntroShown(this)
            }
            .show()
    }

    private fun loadMonths() {
        lifecycleScope.launch {
            val enriched = withContext(Dispatchers.IO) { loadEnrichedSummaries() }
            adapter.submit(enriched)
            val empty = enriched.isEmpty()
            findViewById<View>(R.id.utilitiesEmptyText).visibility = if (empty) View.VISIBLE else View.GONE
            findViewById<View>(R.id.utilitiesRecyclerView).visibility = if (empty) View.GONE else View.VISIBLE
            refreshSetupGuide()
        }
    }

    private suspend fun loadEnrichedSummaries(): List<EnrichedUtilityBillSummary> {
        val dao = manager.utilityDao
        val bills = dao.getAllBills()
        val totals = dao.getBillGrandTotals().associate { it.billId to it.total }
        val totalsByPeriod = bills.associate { (it.year to it.month) to (totals[it.id] ?: 0.0) }
        val meterMonths = dao.getAllMeterReadings().mapNotNull { reading ->
            val epoch = UtilityExcelParser.parsePeriodToEpochDay(reading.periodLabel) ?: return@mapNotNull null
            val date = LocalDate.ofEpochDay(epoch)
            date.year to date.monthValue
        }.toSet()
        return bills.map { bill ->
            val grand = totals[bill.id] ?: 0.0
            val previous = UtilityAnomalyHelper.previousPeriod(bill.year, bill.month)
            EnrichedUtilityBillSummary(
                summary = UtilityBillSummary(bill, grand),
                checklist = UtilityMonthChecklistHelper.fromBill(
                    bill,
                    grand,
                    (bill.year to bill.month) in meterMonths,
                ),
                anomalyPercent = UtilityAnomalyHelper.percentChange(grand, totalsByPeriod[previous]),
                forecastPercent = UtilityForecastHelper.percentVsRecentAverage(
                    grand,
                    UtilityForecastHelper.previousMonthTotals(bill.year, bill.month, totalsByPeriod),
                ),
            )
        }
    }

    private fun refreshPayCategoryRow() {
        lifecycleScope.launch {
            manager.getCategoriesAsync()
            val budgetId = manager.getActiveBudgetId()
            val categoryId = UtilitySetupPreferences.getPayPrimaryCategoryId(this@UtilitiesActivity, budgetId)
            val name = manager.getCategories().firstOrNull { it.id == categoryId }?.name
                ?: getString(R.string.utility_pay_category_not_set)
            val title = "${getString(R.string.utility_pay_category_setting)} · $name"
            MenuRowHelper.bind(
                findViewById(R.id.utilityPayCategoryButton),
                "🏦",
                title,
            )
        }
    }

    private fun pickPayCategory() {
        lifecycleScope.launch {
            manager.getCategoriesAsync()
            val leaves = manager.getCategoriesForExpenses()
            if (leaves.isEmpty()) {
                Toast.makeText(this@UtilitiesActivity, R.string.utility_pay_no_categories, Toast.LENGTH_LONG).show()
                return@launch
            }
            val parents = manager.getRootCategories().associate { it.id to it.name }
            val labels = leaves.map { CategoryMultiPicker.leafLabel(it, parents) }.toTypedArray()
            AlertDialog.Builder(this@UtilitiesActivity)
                .setTitle(R.string.utility_pay_category_setting_title)
                .setItems(labels) { _, which ->
                    val budgetId = manager.getActiveBudgetId()
                    UtilitySetupPreferences.setPayPrimaryCategoryId(
                        this@UtilitiesActivity,
                        leaves[which].id,
                        budgetId,
                    )
                    Toast.makeText(this@UtilitiesActivity, R.string.utility_pay_category_saved, Toast.LENGTH_SHORT).show()
                    refreshPayCategoryRow()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun showAddMonthDialog() {
        lifecycleScope.launch {
            val (templateDone, tariffsDone) = withContext(Dispatchers.IO) {
                val dao = manager.utilityDao
                val sections = dao.getTemplateSectionCount()
                val tariffLines = dao.getTemplateTariffLineCount()
                val filled = dao.getFilledTariffCount()
                (sections > 0) to (tariffLines == 0 || filled >= tariffLines)
            }
            if (!templateDone) {
                showNoTemplateDialog()
                return@launch
            }
            if (tariffsDone) {
                openPickMonthDialog()
            } else {
                showMissingTariffsDialog { openPickMonthDialog() }
            }
        }
    }

    private fun showNoTemplateDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.utility_add_month_no_template_title)
            .setMessage(R.string.utility_add_month_no_template_message)
            .setPositiveButton(R.string.utility_add_month_no_template_action) { _, _ ->
                startActivity(Intent(this, UtilityTemplateActivity::class.java))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showMissingTariffsDialog(onContinue: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(R.string.utility_add_month_tariffs_missing_title)
            .setMessage(R.string.utility_add_month_tariffs_missing_message)
            .setPositiveButton(R.string.utility_add_month_tariffs_missing_action) { _, _ ->
                startActivity(Intent(this, UtilityTariffsActivity::class.java))
            }
            .setNegativeButton(R.string.utility_add_month_anyway) { _, _ -> onContinue() }
            .setNeutralButton(android.R.string.cancel, null)
            .show()
    }

    private fun openPickMonthDialog() {
        val defaultCal = Calendar.getInstance().apply { add(Calendar.MONTH, -1) }
        val currentYear = Calendar.getInstance().get(Calendar.YEAR)
        val years = ((currentYear - 5)..(currentYear + 1)).map { it.toString() }
        val view = layoutInflater.inflate(R.layout.dialog_utility_pick_month, null)
        val monthSpinner = view.findViewById<Spinner>(R.id.monthSpinner)
        val yearSpinner = view.findViewById<Spinner>(R.id.yearSpinner)
        monthSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, monthNames)
        yearSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, years)
        monthSpinner.setSelection(defaultCal.get(Calendar.MONTH))
        yearSpinner.setSelection(years.indexOf(defaultCal.get(Calendar.YEAR).toString()).coerceAtLeast(0))
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.utility_pick_month_title)
            .setView(view)
            .setPositiveButton(R.string.utility_pick_month_next, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val year = years[yearSpinner.selectedItemPosition].toInt()
                val month = monthSpinner.selectedItemPosition + 1
                dialog.dismiss()
                confirmCreateMonth(year, month)
            }
        }
        dialog.show()
    }

    private fun confirmCreateMonth(year: Int, month: Int) {
        val period = UtilityUserTemplate.formatPeriod(year, month)
        lifecycleScope.launch {
            val (existing, hasPrev) = withContext(Dispatchers.IO) {
                val dao = manager.utilityDao
                val already = dao.getBillByPeriod(year, month) != null
                val previous = dao.getAllBills().any {
                    it.year < year || (it.year == year && it.month < month)
                }
                already to previous
            }
            if (existing) {
                Toast.makeText(
                    this@UtilitiesActivity,
                    getString(R.string.utility_month_exists, period),
                    Toast.LENGTH_SHORT,
                ).show()
                return@launch
            }
            val builder = AlertDialog.Builder(this@UtilitiesActivity)
                .setTitle(getString(R.string.utility_new_month_title, period))
                .setMessage(R.string.utility_new_month_message)
                .setPositiveButton(R.string.utility_new_month_create) { _, _ ->
                    createMonthFromTemplate(year, month)
                }
                .setNegativeButton(android.R.string.cancel, null)
            if (hasPrev) {
                builder.setNeutralButton(R.string.utility_copy_prev_with_amounts) { _, _ ->
                    copyMonthFromPrevious(year, month, copyAmounts = true)
                }
            }
            builder.show()
        }
    }

    private fun createMonthFromTemplate(year: Int, month: Int) {
        lifecycleScope.launch(Dispatchers.IO) {
            val billId = UtilityUserTemplate.createBillFromUserTemplate(
                manager.utilityDao,
                year,
                month,
                applyTariffs = true,
            )
            withContext(Dispatchers.Main) {
                openCreatedMonth(billId)
            }
        }
    }

    private fun copyMonthFromPrevious(year: Int, month: Int, copyAmounts: Boolean) {
        lifecycleScope.launch(Dispatchers.IO) {
            val billId = UtilityUserTemplate.copyBillFromPreviousMonth(
                manager.utilityDao,
                year,
                month,
                copyAmounts,
            )
            withContext(Dispatchers.Main) {
                if (billId == null) {
                    Toast.makeText(
                        this@UtilitiesActivity,
                        R.string.utility_copy_prev_none,
                        Toast.LENGTH_LONG,
                    ).show()
                } else {
                    openCreatedMonth(billId)
                }
            }
        }
    }

    private fun openCreatedMonth(billId: Int) {
        loadMonths()
        startActivity(
            Intent(this, UtilityBillActivity::class.java).putExtra(EXTRA_BILL_ID, billId),
        )
    }

    private fun confirmDelete(summary: UtilityBillSummary) {
        val bill = summary.bill
        val period = UtilityUserTemplate.formatPeriod(bill.year, bill.month)
        if (bill.budgetPaidAt != null) {
            val paid = bill.budgetPaymentSummary.ifBlank { MoneyFormat.formatRub(summary.grandTotal) }
            AlertDialog.Builder(this)
                .setTitle(R.string.utility_delete_paid_title)
                .setMessage(getString(R.string.utility_delete_paid_message, paid))
                .setPositiveButton(R.string.utility_delete_paid_confirm) { _, _ ->
                    deleteMonth(bill, reversePayment = true)
                }
                .setNeutralButton(R.string.utility_delete_data_only) { _, _ ->
                    deleteMonth(bill, reversePayment = false)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.utility_delete_month_title, period))
            .setMessage(R.string.utility_delete_month_message)
            .setPositiveButton(R.string.budget_delete_selected) { _, _ ->
                deleteMonth(bill, reversePayment = false)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun deleteMonth(bill: UtilityBillEntity, reversePayment: Boolean) {
        lifecycleScope.launch(Dispatchers.IO) {
            if (reversePayment) {
                val groupId = bill.budgetPaymentGroupId
                if (!groupId.isNullOrBlank()) {
                    manager.repository.cancelTransactionGroup(groupId)
                } else if (bill.budgetPaidAt != null) {
                    val desc = UtilityUserTemplate.paymentDescription(bill.year, bill.month)
                    manager.repository.getExpenseTransactionsByDescription(desc).forEach { tx ->
                        manager.repository.cancelTransaction(tx)
                    }
                }
            }
            manager.utilityDao.deleteBill(bill.id)
            manager.reloadCategoriesFromDatabase()
            withContext(Dispatchers.Main) { loadMonths() }
        }
    }

    private class MonthAdapter(
        private val onOpen: (Int) -> Unit,
        private val onLongClick: (UtilityBillSummary) -> Unit,
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private var rows: List<UtilityMonthRow> = emptyList()
        private var billsByYear: Map<Int, List<UtilityMonthRow.MonthItem>> = emptyMap()
        private val expandedYears = linkedSetOf<Int>()
        private var currentYear = Calendar.getInstance().get(Calendar.YEAR)
        private val monthNames = arrayOf(
            "январь", "февраль", "март", "апрель", "май", "июнь",
            "июль", "август", "сентябрь", "октябрь", "ноябрь", "декабрь",
        )

        fun submit(list: List<EnrichedUtilityBillSummary>) {
            currentYear = Calendar.getInstance().get(Calendar.YEAR)
            billsByYear = list.groupBy { it.summary.bill.year }
                .mapValues { (_, items) ->
                    items.sortedByDescending { it.summary.bill.month }
                        .map { UtilityMonthRow.MonthItem(it) }
                }
            expandedYears.retainAll(billsByYear.keys)
            if (expandedYears.isEmpty()) {
                if (currentYear in billsByYear) {
                    expandedYears += currentYear
                } else {
                    billsByYear.keys.maxOrNull()?.let { expandedYears += it }
                }
            }
            rebuildRows()
        }

        private fun toggleYear(year: Int) {
            if (!expandedYears.add(year)) expandedYears.remove(year)
            rebuildRows()
        }

        private fun rebuildRows() {
            val built = mutableListOf<UtilityMonthRow>()
            billsByYear.toSortedMap(compareByDescending { it }).forEach { (year, months) ->
                val expanded = year in expandedYears
                val yearTotal = months.sumOf { it.summary.grandTotal }
                built += UtilityMonthRow.YearHeader(year, months.size, yearTotal, expanded)
                if (expanded) built += months
            }
            rows = built
            notifyDataSetChanged()
        }

        override fun getItemViewType(position: Int): Int = when (rows[position]) {
            is UtilityMonthRow.YearHeader -> VIEW_YEAR
            is UtilityMonthRow.MonthItem -> VIEW_MONTH
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return if (viewType == VIEW_YEAR) {
                YearHolder(inflater.inflate(R.layout.item_utility_year_header, parent, false))
            } else {
                MonthHolder(inflater.inflate(R.layout.item_utility_month, parent, false))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val row = rows[position]) {
                is UtilityMonthRow.YearHeader -> bindYear(holder as YearHolder, row)
                is UtilityMonthRow.MonthItem -> bindMonth(holder as MonthHolder, row)
            }
        }

        private fun bindYear(holder: YearHolder, row: UtilityMonthRow.YearHeader) {
            holder.title.text = if (row.year == currentYear) {
                "${row.year} (текущий)"
            } else {
                row.year.toString()
            }
            holder.subtitle.text =
                "${row.monthCount} ${monthsWord(row.monthCount)} · ${MoneyFormat.formatRub(row.yearTotal)}"
            holder.expandIcon.text = if (row.expanded) "▲" else "▼"
            holder.itemView.setOnClickListener { toggleYear(row.year) }
        }

        private fun bindMonth(holder: MonthHolder, row: UtilityMonthRow.MonthItem) {
            val ctx = holder.itemView.context
            val item = row.summary
            val monthIndex = item.bill.month - 1
            val monthLabel = monthNames.getOrNull(monthIndex) ?: "?"
            holder.period.text = monthLabel.replaceFirstChar { it.uppercaseChar() }
            if (item.bill.apartmentArea > 0.0) {
                holder.area.visibility = View.VISIBLE
                holder.area.text = ctx.getString(R.string.utility_bill_area_value, item.bill.apartmentArea)
            } else {
                holder.area.visibility = View.GONE
            }
            holder.total.text = MoneyFormat.formatRub(item.grandTotal)
            val bill = item.bill
            if (bill.budgetPaidAt != null && bill.budgetPaymentSummary.isNotBlank()) {
                holder.paymentStatus.visibility = View.VISIBLE
                holder.paymentStatus.setTextColor(ContextCompat.getColor(ctx, R.color.primary_green))
                val paid = ctx.getString(R.string.utility_paid_from_budget, bill.budgetPaymentSummary)
                holder.paymentStatus.text = if (bill.budgetRemainderSummary.isNotBlank()) {
                    paid + "\n" + ctx.getString(R.string.utility_remainder_in_envelope, bill.budgetRemainderSummary)
                } else {
                    paid
                }
            } else {
                holder.paymentStatus.visibility = View.GONE
            }
            val badges = mutableListOf<String>()
            if (row.unpaid) badges += ctx.getString(R.string.utility_unpaid_badge)
            row.anomalyPercent?.let { pct ->
                badges += if (pct > 0) {
                    ctx.getString(R.string.utility_anomaly_badge, pct)
                } else {
                    ctx.getString(R.string.utility_anomaly_badge_negative, pct)
                }
            }
            row.forecastPercent?.let { pct ->
                if (kotlin.math.abs(pct) >= UtilityAnomalyHelper.THRESHOLD_PERCENT) {
                    badges += ctx.getString(R.string.utility_forecast_vs_avg, pct)
                }
            }
            if (badges.isEmpty()) {
                holder.badge.visibility = View.GONE
            } else {
                holder.badge.visibility = View.VISIBLE
                holder.badge.text = badges.joinToString(" · ")
                val colorRes = when {
                    row.unpaid -> R.color.expense_red
                    (row.anomalyPercent ?: 0) > 0 -> R.color.expense_red
                    else -> R.color.settings_orange
                }
                holder.badge.setTextColor(ContextCompat.getColor(ctx, colorRes))
            }
            holder.checklist.visibility = View.VISIBLE
            holder.checklist.text = UtilityMonthChecklistHelper.formatCompact(ctx, row.checklist)
            holder.itemView.setOnClickListener { onOpen(item.bill.id) }
            holder.itemView.setOnLongClickListener {
                onLongClick(item)
                true
            }
        }

        override fun getItemCount(): Int = rows.size

        class MonthHolder(v: View) : RecyclerView.ViewHolder(v) {
            val period: TextView = v.findViewById(R.id.periodText)
            val area: TextView = v.findViewById(R.id.areaText)
            val total: TextView = v.findViewById(R.id.totalText)
            val badge: TextView = v.findViewById(R.id.badgeText)
            val paymentStatus: TextView = v.findViewById(R.id.paymentStatusText)
            val checklist: TextView = v.findViewById(R.id.checklistText)
        }

        class YearHolder(v: View) : RecyclerView.ViewHolder(v) {
            val title: TextView = v.findViewById(R.id.yearHeaderTitle)
            val subtitle: TextView = v.findViewById(R.id.yearHeaderSubtitle)
            val expandIcon: TextView = v.findViewById(R.id.yearHeaderExpandIcon)
        }

        companion object {
            private const val VIEW_YEAR = 0
            private const val VIEW_MONTH = 1

            fun monthsWord(count: Int): String {
                val n10 = count % 10
                val n100 = count % 100
                return when {
                    n10 == 1 && n100 != 11 -> "месяц"
                    n10 in 2..4 && n100 !in 12..14 -> "месяца"
                    else -> "месяцев"
                }
            }
        }
    }
}

private sealed class UtilityMonthRow {
    data class YearHeader(
        val year: Int,
        val monthCount: Int,
        val yearTotal: Double,
        val expanded: Boolean,
    ) : UtilityMonthRow()

    data class MonthItem(val enriched: EnrichedUtilityBillSummary) : UtilityMonthRow() {
        val summary: UtilityBillSummary get() = enriched.summary
        val anomalyPercent: Int? get() = enriched.anomalyPercent
        val forecastPercent: Int? get() = enriched.forecastPercent
        val checklist: UtilityMonthChecklistHelper.Checklist get() = enriched.checklist
        val unpaid: Boolean get() = summary.bill.budgetPaidAt == null && summary.grandTotal > 0.0
    }
}
