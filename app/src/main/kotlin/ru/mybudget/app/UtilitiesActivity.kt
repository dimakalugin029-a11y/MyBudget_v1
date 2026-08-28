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
import ru.mybudget.app.backup.AutoBackupWorker
import ru.mybudget.app.setup.ActivePropertyPreferences
import ru.mybudget.app.setup.UtilityPaymentReminderPreferences
import ru.mybudget.app.setup.UtilitySetupPreferences
import ru.mybudget.app.utilities.UtilityPropertyCopyHelper
import ru.mybudget.app.utilities.EnrichedUtilityBillSummary
import ru.mybudget.app.utilities.MeterExcelFormat
import ru.mybudget.app.utilities.UtilityAnomalyHelper
import ru.mybudget.app.utilities.UtilityBillSummary
import ru.mybudget.app.utilities.UtilityExcelExporter
import ru.mybudget.app.utilities.UtilityExcelIo
import ru.mybudget.app.utilities.UtilityExcelParser
import ru.mybudget.app.utilities.UtilityForecastHelper
import ru.mybudget.app.utilities.UtilityLegacyPaymentHelper
import ru.mybudget.app.utilities.UtilityMonthChecklistHelper
import ru.mybudget.app.utilities.UtilityPhotoPreferences
import ru.mybudget.app.utilities.UtilitySetupGuideHelper
import ru.mybudget.app.utilities.UtilitySetupState
import ru.mybudget.app.utilities.UtilityUserTemplate
import java.time.LocalDate
import java.util.Calendar

class UtilitiesActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_BILL_ID = "utility_bill_id"
        const val EXTRA_PROPERTY_ID = "utility_property_id"
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
            val ok = UtilityExcelIo.saveCommunal(contentResolver, uri, manager.utilityDao, propertyId())
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

    private val photoFolderLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        val persisted = runCatching {
            contentResolver.takePersistableUriPermission(uri, flags)
        }.isSuccess || runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }.isSuccess
        if (!persisted) {
            Toast.makeText(this, R.string.auto_backup_folder_unavailable, Toast.LENGTH_LONG).show()
            return@registerForActivityResult
        }
        UtilityPhotoPreferences.setFolderUri(this, uri)
        refreshPhotoFolderRow()
    }
    private val monthNames = arrayOf(
        "Январь", "Февраль", "Март", "Апрель", "Май", "Июнь",
        "Июль", "Август", "Сентябрь", "Октябрь", "Ноябрь", "Декабрь",
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_utilities)
        manager = BudgetManager.getInstance(this)
        lifecycleScope.launch(Dispatchers.IO) {
            UtilityPropertyCopyHelper.ensureDefaultProperty(manager.utilityDao)
            val activeId = ActivePropertyPreferences.getActivePropertyId(this@UtilitiesActivity)
            if (manager.utilityDao.getPropertyById(activeId) == null) {
                val fallback = manager.utilityDao.getAllProperties().firstOrNull()?.id
                    ?: ActivePropertyPreferences.DEFAULT_PROPERTY_ID
                ActivePropertyPreferences.setActivePropertyId(this@UtilitiesActivity, fallback)
            }
        }
        intent.getIntExtra(EXTRA_PROPERTY_ID, 0).takeIf { it > 0 }?.let {
            ActivePropertyPreferences.setActivePropertyId(this, it)
        }
        ScreenHeaderHelper.setup(this, getString(R.string.main_menu_utilities), getString(R.string.main_icon_utilities))
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
        refreshPropertyRow()
        MenuRowHelper.bind(
            findViewById(R.id.utilityPayCategoryButton),
            "🏦",
            getString(R.string.utility_pay_category_setting),
        ) { pickPayCategory() }
        refreshPaymentDayRow()
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
        MenuRowHelper.bind(
            findViewById(R.id.markLegacyPaidButton),
            "✓",
            getString(R.string.utility_mark_legacy_paid_button),
        ) { confirmMarkPastAsLegacyPaid() }
        refreshPhotoFolderRow()
        findViewById<View>(R.id.addUtilityMonthButton).setOnClickListener { showAddMonthDialog() }
        val sheetBehavior = CollapsibleBottomSheetHelper.attach(
            findViewById(R.id.utilitiesActionsSheet),
            findViewById(R.id.bottomSheetHeader),
            findViewById(R.id.bottomSheetChevron),
            recycler,
            resources.getDimensionPixelSize(R.dimen.space_8),
        )
        val addMonthButton = findViewById<View>(R.id.addUtilityMonthButton)
        val sheetHeader = findViewById<View>(R.id.bottomSheetHeader)
        val extraPadding = resources.getDimensionPixelSize(R.dimen.space_8)
        sheetHeader.post {
            addMonthButton.post {
                val peek = sheetHeader.height + addMonthButton.height + extraPadding
                if (peek > 0) {
                    sheetBehavior.peekHeight = peek
                    recycler.setPadding(
                        recycler.paddingLeft,
                        recycler.paddingTop,
                        recycler.paddingRight,
                        peek + extraPadding,
                    )
                }
            }
        }
        if (!UtilitySetupPreferences.hasSeenIntro(this)) {
            showIntroDialog()
        }
    }

    override fun onResume() {
        super.onResume()
        loadMonths()
        refreshPropertyRow()
        refreshPayCategoryRow()
        refreshPaymentDayRow()
        refreshPhotoFolderRow()
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
            val result = UtilityExcelIo.importCommunal(contentResolver, uri, manager.utilityDao, propertyId(), replace)
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
            val state = withContext(Dispatchers.IO) { UtilitySetupState.load(manager.utilityDao, propertyId()) }
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

    private fun propertyId() = ActivePropertyPreferences.getActivePropertyId(this)

    private suspend fun loadEnrichedSummaries(): List<EnrichedUtilityBillSummary> {
        val dao = manager.utilityDao
        val activePropertyId = propertyId()
        val bills = dao.getAllBills(activePropertyId)
        val totals = dao.getBillGrandTotals().associate { it.billId to it.total }
        val totalsByPeriod = bills.associate { (it.year to it.month) to (totals[it.id] ?: 0.0) }
        val meterMonths = dao.getAllMeterReadings(activePropertyId).mapNotNull { reading ->
            val epoch = UtilityExcelParser.parsePeriodToEpochDay(reading.periodLabel) ?: return@mapNotNull null
            val date = LocalDate.ofEpochDay(epoch)
            date.year to date.monthValue
        }.toSet()
        val photoCounts = dao.getPhotoCountsByBill().associate { it.billId to it.count }
        return bills.map { bill ->
            val grand = totals[bill.id] ?: 0.0
            val previous = UtilityAnomalyHelper.previousPeriod(bill.year, bill.month)
            EnrichedUtilityBillSummary(
                summary = UtilityBillSummary(bill, grand),
                checklist = UtilityMonthChecklistHelper.fromBill(
                    bill,
                    grand,
                    (bill.year to bill.month) in meterMonths,
                    photoCounts[bill.id] ?: 0,
                ),
                anomalyPercent = UtilityAnomalyHelper.percentChange(grand, totalsByPeriod[previous]),
                forecastPercent = UtilityForecastHelper.percentVsRecentAverage(
                    grand,
                    UtilityForecastHelper.previousMonthTotals(bill.year, bill.month, totalsByPeriod),
                ),
            )
        }
    }

    private fun refreshPropertyRow() {
        lifecycleScope.launch {
            val property = withContext(Dispatchers.IO) {
                manager.utilityDao.getPropertyById(propertyId())
            }
            val name = property?.name ?: getString(R.string.utility_properties_setting)
            val title = "${getString(R.string.utility_properties_setting)} · $name"
            MenuRowHelper.bind(
                findViewById(R.id.utilityPropertyButton),
                "🏠",
                title,
            ) { startActivity(Intent(this@UtilitiesActivity, UtilityPropertiesActivity::class.java)) }
        }
    }

    private fun refreshPaymentDayRow() {
        val dayLabel = PlannedObligationHelper.dueDayLabel(
            this,
            UtilityPaymentReminderPreferences.paymentDay(this, propertyId()),
        )
        val title = "${getString(R.string.utility_payment_day_setting)}\n$dayLabel"
        MenuRowHelper.bind(
            findViewById(R.id.utilityPaymentDayButton),
            "📅",
            title,
        ) { pickPaymentDay() }
    }

    private fun pickPaymentDay() {
        val labels = paymentDaySpinnerLabels()
        val currentDay = UtilityPaymentReminderPreferences.paymentDay(this, propertyId())
        AlertDialog.Builder(this)
            .setTitle(R.string.utility_payment_day_setting)
            .setSingleChoiceItems(
                labels.toTypedArray(),
                PlannedObligationHelper.dueDaySpinnerPosition(currentDay),
            ) { dialog, which ->
                UtilityPaymentReminderPreferences.setPaymentDay(
                    this,
                    propertyId(),
                    PlannedObligationHelper.dueDayFromSpinnerPosition(which),
                )
                Toast.makeText(this, R.string.utility_payment_day_saved, Toast.LENGTH_SHORT).show()
                refreshPaymentDayRow()
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun paymentDaySpinnerLabels(): List<String> =
        (1..31).map { getString(R.string.obligations_due_day_number, it) } +
            getString(R.string.obligations_due_day_last)

    private fun refreshPhotoFolderRow() {
        val uri = UtilityPhotoPreferences.folderUri(this)
        val subtitle = if (uri == null) {
            getString(R.string.utility_photo_folder_not_selected)
        } else {
            getString(
                R.string.utility_photo_folder_selected,
                AutoBackupWorker.folderDisplayName(this, uri),
            )
        }
        val title = "${getString(R.string.utility_photo_folder_setting)}\n$subtitle"
        MenuRowHelper.bind(
            findViewById(R.id.utilityPhotoFolderButton),
            "📁",
            title,
        ) { photoFolderLauncher.launch(uri) }
    }

    private fun refreshPayCategoryRow() {
        lifecycleScope.launch {
            manager.getCategoriesAsync()
            val budgetId = manager.getActiveBudgetId()
            val categoryId = UtilitySetupPreferences.getPayPrimaryCategoryId(this@UtilitiesActivity, propertyId())
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
                    val activePropertyId = propertyId()
                    UtilitySetupPreferences.setPayPrimaryCategoryId(
                        this@UtilitiesActivity,
                        leaves[which].id,
                        activePropertyId,
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
                val activePropertyId = propertyId()
                val sections = dao.getTemplateSectionCount(activePropertyId)
                val tariffLines = dao.getTemplateTariffLineCount(activePropertyId)
                val filled = dao.getFilledTariffCount(activePropertyId)
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
                val activePropertyId = propertyId()
                val already = dao.getBillByPeriod(activePropertyId, year, month) != null
                val previous = dao.getAllBills(activePropertyId).any {
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
            if (hasPrev) {
                val view = layoutInflater.inflate(R.layout.dialog_utility_new_month, null)
                val dialog = AlertDialog.Builder(this@UtilitiesActivity)
                    .setTitle(getString(R.string.utility_new_month_title, period))
                    .setView(view)
                    .setNegativeButton(android.R.string.cancel, null)
                    .create()
                view.findViewById<View>(R.id.newMonthCreateButton).setOnClickListener {
                    dialog.dismiss()
                    createMonthFromTemplate(year, month)
                }
                view.findViewById<View>(R.id.newMonthCopyAmountsButton).setOnClickListener {
                    dialog.dismiss()
                    copyMonthFromPrevious(year, month, copyAmounts = true)
                }
                view.findViewById<View>(R.id.newMonthCopyStructureButton).setOnClickListener {
                    dialog.dismiss()
                    copyMonthFromPrevious(year, month, copyAmounts = false)
                }
                dialog.show()
            } else {
                AlertDialog.Builder(this@UtilitiesActivity)
                    .setTitle(getString(R.string.utility_new_month_title, period))
                    .setMessage(R.string.utility_new_month_message)
                    .setPositiveButton(R.string.utility_new_month_create) { _, _ ->
                        createMonthFromTemplate(year, month)
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }
    }

    private fun createMonthFromTemplate(year: Int, month: Int) {
        lifecycleScope.launch(Dispatchers.IO) {
            val billId = UtilityUserTemplate.createBillFromUserTemplate(
                manager.utilityDao,
                propertyId(),
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
                propertyId(),
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

    private fun confirmMarkPastAsLegacyPaid() {
        lifecycleScope.launch {
            val count = withContext(Dispatchers.IO) {
                UtilityLegacyPaymentHelper.countPastUnpaidWithAmount(manager.utilityDao)
            }
            if (count == 0) {
                Toast.makeText(this@UtilitiesActivity, R.string.utility_mark_legacy_paid_none, Toast.LENGTH_LONG).show()
                return@launch
            }
            AlertDialog.Builder(this@UtilitiesActivity)
                .setTitle(R.string.utility_mark_legacy_paid_title)
                .setMessage(resources.getQuantityString(R.plurals.utility_mark_legacy_paid_message, count, count))
                .setPositiveButton(R.string.utility_mark_legacy_paid_confirm) { _, _ ->
                    markPastAsLegacyPaid()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun markPastAsLegacyPaid() {
        lifecycleScope.launch {
            val count = withContext(Dispatchers.IO) {
                UtilityLegacyPaymentHelper.markPastAsLegacyPaid(this@UtilitiesActivity, manager.utilityDao)
            }
            Toast.makeText(
                this@UtilitiesActivity,
                resources.getQuantityString(R.plurals.utility_mark_legacy_paid_success, count, count),
                Toast.LENGTH_LONG,
            ).show()
            loadMonths()
        }
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
            if (reversePayment && bill.budgetPaidAt != null && !UtilityLegacyPaymentHelper.isLegacyPaid(bill)) {
                val groupId = bill.budgetPaymentGroupId
                if (!groupId.isNullOrBlank()) {
                    manager.repository.cancelTransactionGroup(groupId)
                } else {
                    val propertyName = manager.utilityDao.getPropertyById(bill.propertyId)?.name.orEmpty()
                    val desc = UtilityUserTemplate.paymentDescription(propertyName, bill.year, bill.month)
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
