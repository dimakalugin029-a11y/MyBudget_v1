package ru.mybudget.app

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.mybudget.app.data.UtilityBillEntity
import ru.mybudget.app.setup.UtilitySetupPreferences
import ru.mybudget.app.utilities.UtilityUserTemplate
import java.util.Calendar

class UtilitiesActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_BILL_ID = "utility_bill_id"
    }

    private lateinit var manager: BudgetManager
    private lateinit var adapter: MonthAdapter
    private val monthNames = arrayOf(
        "Январь", "Февраль", "Март", "Апрель", "Май", "Июнь",
        "Июль", "Август", "Сентябрь", "Октябрь", "Ноябрь", "Декабрь",
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_utilities)
        manager = BudgetManager.getInstance(this)
        ScreenHeaderHelper.setup(this, getString(R.string.main_menu_utilities))
        bindSetupGuide()
        adapter = MonthAdapter(
            onOpen = { billId ->
                startActivity(
                    Intent(this, UtilityBillActivity::class.java).putExtra(EXTRA_BILL_ID, billId),
                )
            },
            onLongClick = { bill -> confirmDelete(bill) },
        )
        findViewById<RecyclerView>(R.id.utilitiesRecyclerView).apply {
            layoutManager = LinearLayoutManager(this@UtilitiesActivity)
            this.adapter = this@UtilitiesActivity.adapter
        }
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
        ) { Toast.makeText(this, R.string.settings_backup_hint, Toast.LENGTH_LONG).show() }
        MenuRowHelper.bind(
            findViewById(R.id.importExcelButton),
            "📥",
            getString(R.string.utility_import_excel),
        ) { Toast.makeText(this, R.string.settings_backup_hint, Toast.LENGTH_LONG).show() }
        findViewById<View>(R.id.addUtilityMonthButton).setOnClickListener { showAddMonthDialog() }
        val sheet = findViewById<View>(R.id.utilitiesActionsSheet)
        val header = findViewById<View>(R.id.bottomSheetHeader)
        val chevron = findViewById<TextView>(R.id.bottomSheetChevron)
        val peek = resources.getDimensionPixelSize(R.dimen.touch_min_size) * 3
        CollapsibleBottomSheetHelper.attach(sheet, header, chevron, peek)
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

    private fun bindSetupGuide() {
        val guide = findViewById<View>(R.id.utilitiesSetupGuide)
        findViewById<TextView>(R.id.utilitySetupStep1).setText(R.string.utility_setup_step1)
        findViewById<TextView>(R.id.utilitySetupStep2).setText(R.string.utility_setup_step2)
        findViewById<TextView>(R.id.utilitySetupStep3).setText(R.string.utility_setup_step3)
        findViewById<View>(R.id.utilitySetupDismiss).setOnClickListener {
            UtilitySetupPreferences.dismissGuide(this)
            guide.visibility = View.GONE
        }
        refreshSetupGuide()
    }

    private fun refreshSetupGuide() {
        val guide = findViewById<View>(R.id.utilitiesSetupGuide)
        guide.visibility = if (UtilitySetupPreferences.isGuideDismissed(this)) View.GONE else View.VISIBLE
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
            val (bills, totals) = withContext(Dispatchers.IO) {
                val dao = manager.utilityDao
                dao.getAllBills() to dao.getBillGrandTotals().associate { it.billId to it.total }
            }
            adapter.submit(bills, totals)
            val empty = bills.isEmpty()
            findViewById<View>(R.id.utilitiesEmptyText).visibility = if (empty) View.VISIBLE else View.GONE
            findViewById<View>(R.id.utilitiesRecyclerView).visibility = if (empty) View.GONE else View.VISIBLE
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
            val existing = withContext(Dispatchers.IO) {
                manager.utilityDao.getBillByPeriod(year, month) != null
            }
            if (existing) {
                Toast.makeText(
                    this@UtilitiesActivity,
                    getString(R.string.utility_month_exists, period),
                    Toast.LENGTH_SHORT,
                ).show()
                return@launch
            }
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

    private fun createMonthFromTemplate(year: Int, month: Int) {
        lifecycleScope.launch(Dispatchers.IO) {
            val billId = UtilityUserTemplate.createBillFromUserTemplate(
                manager.utilityDao,
                year,
                month,
                applyTariffs = true,
            )
            withContext(Dispatchers.Main) {
                loadMonths()
                startActivity(
                    Intent(this@UtilitiesActivity, UtilityBillActivity::class.java)
                        .putExtra(EXTRA_BILL_ID, billId),
                )
            }
        }
    }

    private fun confirmDelete(bill: UtilityBillEntity) {
        val period = UtilityUserTemplate.formatPeriod(bill.year, bill.month)
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.utility_delete_month_title, period))
            .setMessage(R.string.utility_delete_month_message)
            .setPositiveButton(R.string.budget_delete_selected) { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    manager.utilityDao.deleteBill(bill.id)
                    withContext(Dispatchers.Main) { loadMonths() }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private class MonthAdapter(
        private val onOpen: (Int) -> Unit,
        private val onLongClick: (UtilityBillEntity) -> Unit,
    ) : RecyclerView.Adapter<MonthAdapter.Holder>() {
        private var bills: List<UtilityBillEntity> = emptyList()
        private var totals: Map<Int, Double> = emptyMap()

        fun submit(list: List<UtilityBillEntity>, grandTotals: Map<Int, Double>) {
            bills = list
            totals = grandTotals
            notifyDataSetChanged()
        }

        class Holder(v: View) : RecyclerView.ViewHolder(v) {
            val period: TextView = v.findViewById(R.id.periodText)
            val area: TextView = v.findViewById(R.id.areaText)
            val total: TextView = v.findViewById(R.id.totalText)
            val badge: TextView = v.findViewById(R.id.badgeText)
            val paymentStatus: TextView = v.findViewById(R.id.paymentStatusText)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_utility_month, parent, false)
            return Holder(v)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val bill = bills[position]
            val ctx = holder.itemView.context
            holder.period.text = UtilityUserTemplate.formatPeriod(bill.year, bill.month)
            if (bill.apartmentArea > 0.0) {
                holder.area.visibility = View.VISIBLE
                holder.area.text = ctx.getString(R.string.utility_bill_area_value, bill.apartmentArea)
            } else {
                holder.area.visibility = View.GONE
            }
            holder.total.text = MoneyFormat.formatRub(totals[bill.id] ?: 0.0)
            if (bill.budgetPaidAt == null) {
                holder.badge.visibility = View.VISIBLE
                holder.badge.text = ctx.getString(R.string.utility_unpaid_badge)
                holder.paymentStatus.visibility = View.GONE
            } else {
                holder.badge.visibility = View.GONE
                holder.paymentStatus.visibility = View.VISIBLE
                val summary = bill.budgetPaymentSummary.ifBlank { MoneyFormat.formatRub(totals[bill.id] ?: 0.0) }
                holder.paymentStatus.text = ctx.getString(R.string.utility_paid_from_budget, summary)
            }
            holder.itemView.setOnClickListener { onOpen(bill.id) }
            holder.itemView.setOnLongClickListener {
                onLongClick(bill)
                true
            }
        }

        override fun getItemCount(): Int = bills.size
    }
}
