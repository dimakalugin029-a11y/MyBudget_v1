package ru.mybudget.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import android.widget.ProgressBar
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
import ru.mybudget.app.data.UtilityMeterInfoEntity
import ru.mybudget.app.utilities.MeterCatalogSummary
import ru.mybudget.app.utilities.MeterDateParser
import ru.mybudget.app.utilities.MeterExcelFormat
import ru.mybudget.app.utilities.MeterRepository
import ru.mybudget.app.utilities.MeterWaterTotals
import ru.mybudget.app.utilities.UtilityExcelExporter
import ru.mybudget.app.utilities.UtilityExcelIo
import ru.mybudget.app.utilities.UtilityMeterDialogs
import java.time.LocalDate

class UtilityMetersActivity : AppCompatActivity() {
    private lateinit var repository: MeterRepository
    private lateinit var adapter: MeterGroupAdapter
    private var pendingCreateIsTemplate = false
    private var pendingImportUri: Uri? = null
    private var loadingDialog: AlertDialog? = null

    private val createXlsxLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument(MeterExcelFormat.XLSX_MIME),
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        val template = pendingCreateIsTemplate
        showLoading(getString(R.string.settings_export_loading))
        lifecycleScope.launch {
            val ok = if (template) {
                UtilityExcelIo.saveTemplate(contentResolver, uri)
            } else {
                UtilityExcelIo.saveMeters(contentResolver, uri, repositoryDao())
            }
            hideLoading()
            Toast.makeText(
                this@UtilityMetersActivity,
                if (ok) {
                    if (template) R.string.meter_excel_template_saved else R.string.export_excel_success
                } else {
                    R.string.export_excel_failed
                },
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    private val importXlsxLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        pendingImportUri = uri
        AlertDialog.Builder(this)
            .setTitle(R.string.meter_excel_import_title)
            .setMessage(R.string.meter_excel_import_message)
            .setPositiveButton(R.string.meter_excel_import_replace) { _, _ -> importMeters(replace = true) }
            .setNeutralButton(R.string.meter_excel_import_add) { _, _ -> importMeters(replace = false) }
            .setNegativeButton(android.R.string.cancel) { _, _ -> pendingImportUri = null }
            .show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_utility_meters)
        ScreenHeaderHelper.setup(this, getString(R.string.utility_meters_title))
        setupExcelMenu()
        findViewById<View>(R.id.metersExcelHint)?.let {
            ScreenHintHelper.bind(
                this,
                it,
                ScreenHintHelper.Keys.UTILITY_METERS_EXCEL,
                R.string.hint_utility_meters_excel,
                showHelpLink = false,
            )
        }
        repository = MeterRepository(BudgetManager.getInstance(this).utilityDao)
        adapter = MeterGroupAdapter(
            onOpenHistory = { openHistory(it) },
            onEdit = { summary ->
                UtilityMeterDialogs.showEditMeter(this, lifecycleScope, repository, summary.info) { loadMeters() }
            },
            onDelete = { summary ->
                UtilityMeterDialogs.showDeleteMeter(
                    this,
                    lifecycleScope,
                    repository,
                    summary.info,
                    summary.readingsCount,
                ) { loadMeters() }
            },
        )
        findViewById<RecyclerView>(R.id.metersRecyclerView).apply {
            layoutManager = LinearLayoutManager(this@UtilityMetersActivity)
            adapter = this@UtilityMetersActivity.adapter
        }
        MenuRowHelper.bind(
            findViewById(R.id.metersVerificationButton),
            "📅",
            getString(R.string.meter_verification_button),
        ) { startActivity(Intent(this, UtilityMeterVerificationActivity::class.java)) }
        findViewById<View>(R.id.metersBatchButton).setOnClickListener {
            startActivity(Intent(this, UtilityMetersBatchActivity::class.java))
        }
        findViewById<View>(R.id.metersAddButton).setOnClickListener {
            UtilityMeterDialogs.showAddMeter(this, lifecycleScope, repository) { loadMeters() }
        }
        val sheet = findViewById<View>(R.id.metersActionsSheet)
        val header = findViewById<View>(R.id.bottomSheetHeader)
        val chevron = findViewById<TextView>(R.id.bottomSheetChevron)
        val extra = resources.getDimensionPixelSize(R.dimen.space_8)
        CollapsibleBottomSheetHelper.attach(
            sheet,
            header,
            chevron,
            findViewById(R.id.metersRecyclerView),
            extra,
        )
    }

    override fun onResume() {
        super.onResume()
        loadMeters()
    }

    override fun onDestroy() {
        hideLoading()
        super.onDestroy()
    }

    private fun setupExcelMenu() {
        ScreenHeaderHelper.bindAction(
            this,
            android.R.drawable.ic_menu_more,
            R.string.transactions_more_menu,
        ) {
            PopupMenu(this, findViewById(R.id.screenHeaderAction)).apply {
                menu.add(0, 1, 0, getString(R.string.meter_excel_template_button))
                menu.add(0, 2, 1, getString(R.string.meter_excel_import_button))
                menu.add(0, 3, 2, getString(R.string.export_meters_excel))
                setOnMenuItemClickListener { item ->
                    when (item.itemId) {
                        1 -> {
                            pendingCreateIsTemplate = true
                            createXlsxLauncher.launch(UtilityExcelExporter.suggestedMetersTemplateFileName())
                        }
                        2 -> importXlsxLauncher.launch(MeterExcelFormat.XLSX_OPEN_MIME_TYPES)
                        3 -> {
                            pendingCreateIsTemplate = false
                            createXlsxLauncher.launch(UtilityExcelExporter.suggestedMetersFileName())
                        }
                    }
                    true
                }
                show()
            }
        }
    }

    private fun importMeters(replace: Boolean) {
        val uri = pendingImportUri ?: return
        pendingImportUri = null
        showLoading(getString(R.string.settings_import_loading))
        lifecycleScope.launch {
            val result = UtilityExcelIo.importMeters(contentResolver, uri, repositoryDao(), replace)
            hideLoading()
            result.fold(
                onSuccess = { imported ->
                    if (imported.catalogEntries == 0 && imported.readingsImported == 0) {
                        Toast.makeText(
                            this@UtilityMetersActivity,
                            R.string.meter_excel_import_empty,
                            Toast.LENGTH_LONG,
                        ).show()
                    } else {
                        Toast.makeText(
                            this@UtilityMetersActivity,
                            getString(
                                R.string.meter_excel_import_success,
                                imported.readingsImported,
                                imported.readingsSkipped,
                            ),
                            Toast.LENGTH_LONG,
                        ).show()
                        loadMeters()
                    }
                },
                onFailure = {
                    Toast.makeText(this@UtilityMetersActivity, R.string.import_excel_failed, Toast.LENGTH_LONG).show()
                },
            )
        }
    }

    private fun repositoryDao() = BudgetManager.getInstance(this).utilityDao

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

    private fun openHistory(summary: MeterCatalogSummary) {
        startActivity(
            Intent(this, UtilityMeterHistoryActivity::class.java)
                .putExtra(UtilityMeterHistoryActivity.EXTRA_GROUP, summary.info.groupName)
                .putExtra(UtilityMeterHistoryActivity.EXTRA_METER, summary.info.meterName),
        )
    }

    private fun loadMeters() {
        findViewById<ProgressBar>(R.id.metersProgressBar).visibility = View.VISIBLE
        findViewById<View>(R.id.metersEmptyText).visibility = View.GONE
        lifecycleScope.launch {
            val (summaries, totals) = withContext(Dispatchers.IO) {
                val list = repository.getMeterCatalogSummaries()
                list to repository.waterTotals(list)
            }
            findViewById<ProgressBar>(R.id.metersProgressBar).visibility = View.GONE
            adapter.submit(summaries)
            bindWaterTotals(totals)
            val empty = summaries.isEmpty()
            findViewById<View>(R.id.metersEmptyText).visibility = if (empty) View.VISIBLE else View.GONE
            findViewById<View>(R.id.metersRecyclerView).visibility = if (empty) View.GONE else View.VISIBLE
        }
    }

    private fun bindWaterTotals(totals: MeterWaterTotals) {
        val strip = findViewById<View>(R.id.metersTotalsStrip)
        strip.visibility = if (totals.hasAny) View.VISIBLE else View.GONE
        if (!totals.hasAny) return
        findViewById<TextView>(R.id.metersHvsTotal).text = getString(
            R.string.meter_totals_compact_hvs,
            formatCompactTotal(totals.hvsReadingSum, totals.hvsMeterCount, totals.hasHvs, totals.hvsConsumptionSum),
        )
        findViewById<TextView>(R.id.metersGvsTotal).text = getString(
            R.string.meter_totals_compact_gvs,
            formatCompactTotal(totals.gvsReadingSum, totals.gvsMeterCount, totals.hasGvs, totals.gvsConsumptionSum),
        )
    }

    private fun formatCompactTotal(sum: Double, meterCount: Int, hasMeters: Boolean, consumptionSum: Double): String {
        if (!hasMeters) return "—"
        val countSuffix = if (meterCount > 1) {
            " (${getString(R.string.meter_totals_meters_count, meterCount)})"
        } else {
            ""
        }
        val consumptionSuffix = if (consumptionSum > 0.0) {
            " · ${getString(R.string.meter_totals_consumption, MoneyFormat.format(consumptionSum))}"
        } else {
            ""
        }
        return MoneyFormat.format(sum) + countSuffix + consumptionSuffix
    }

    private class MeterGroupAdapter(
        private val onOpenHistory: (MeterCatalogSummary) -> Unit,
        private val onEdit: (MeterCatalogSummary) -> Unit,
        private val onDelete: (MeterCatalogSummary) -> Unit,
    ) : RecyclerView.Adapter<MeterGroupAdapter.Holder>() {
        private var items: List<MeterCatalogSummary> = emptyList()
        private var todayEpochDay = LocalDate.now().toEpochDay()

        fun submit(list: List<MeterCatalogSummary>) {
            todayEpochDay = LocalDate.now().toEpochDay()
            items = list
            notifyDataSetChanged()
        }

        class Holder(v: View) : RecyclerView.ViewHolder(v) {
            val groupTitle: TextView = v.findViewById(R.id.meterGroupTitle)
            val subtitle: TextView = v.findViewById(R.id.meterGroupSubtitle)
            val lastReading: TextView = v.findViewById(R.id.meterGroupLastReading)
            val verification: TextView = v.findViewById(R.id.meterGroupVerification)
            val progress: TextView = v.findViewById(R.id.meterGroupProgress)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_utility_meter_group, parent, false)
            return Holder(v)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = items[position]
            val info = item.info
            val group = info.groupName.trim()
            holder.groupTitle.text = info.meterName
            holder.subtitle.text = if (group.isNotBlank()) {
                "$group · записей: ${item.readingsCount}"
            } else {
                "Записей: ${item.readingsCount}"
            }
            val last = item.lastReading
            if (last != null) {
                val periodShown = MeterDateParser.formatPeriodLabelForDisplay(last.periodLabel)
                val cons = last.consumption?.let { " · расход ${MoneyFormat.format(it)}" } ?: ""
                holder.lastReading.text = "Последнее: $periodShown — показание ${MoneyFormat.format(last.readingValue)}$cons"
            } else {
                holder.lastReading.setText(R.string.meter_no_readings_yet)
            }
            holder.lastReading.visibility = View.VISIBLE
            bindVerification(holder, info)
            val hint = item.progressHint
            if (!hint.isNullOrBlank()) {
                holder.progress.visibility = View.VISIBLE
                holder.progress.text = hint
            } else {
                holder.progress.visibility = View.GONE
            }
            holder.itemView.setOnClickListener { onOpenHistory(item) }
            holder.itemView.setOnLongClickListener {
                AlertDialog.Builder(holder.itemView.context)
                    .setItems(arrayOf("Изменить", "Удалить")) { _, which ->
                        if (which == 0) onEdit(item) else onDelete(item)
                    }
                    .show()
                true
            }
        }

        private fun bindVerification(holder: Holder, info: UtilityMeterInfoEntity) {
            if (!shouldShowVerification(info)) {
                holder.verification.visibility = View.GONE
                return
            }
            val ctx = holder.itemView.context
            val label = info.verificationDateLabel.trim()
            val epoch = info.verificationEpochDay
            val text = if (epoch != null && epoch < todayEpochDay) {
                ctx.getString(R.string.meter_verification_overdue, label)
            } else {
                ctx.getString(R.string.meter_verification_until, label)
            }
            val colorRes = when {
                epoch != null && epoch < todayEpochDay -> R.color.expense_red
                epoch != null && epoch - todayEpochDay <= 30 -> R.color.settings_orange
                else -> R.color.text_secondary
            }
            holder.verification.visibility = View.VISIBLE
            holder.verification.text = text
            holder.verification.setTextColor(ContextCompat.getColor(ctx, colorRes))
        }

        private fun shouldShowVerification(info: UtilityMeterInfoEntity): Boolean {
            val label = info.verificationDateLabel.trim()
            if (label.isBlank() || !MeterDateParser.looksLikeVerificationDate(label)) return false
            val epoch = info.verificationEpochDay ?: return true
            return epoch - todayEpochDay <= 90
        }

        override fun getItemCount(): Int = items.size
    }
}
