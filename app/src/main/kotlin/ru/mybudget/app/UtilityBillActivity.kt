package ru.mybudget.app

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.mybudget.app.data.UtilityBillEntity
import ru.mybudget.app.data.UtilityLineItemEntity
import ru.mybudget.app.utilities.UtilityBillDetail
import ru.mybudget.app.utilities.UtilityLegacyPaymentHelper
import ru.mybudget.app.utilities.UtilityMeterBillLinker
import ru.mybudget.app.utilities.UtilityPayCategoryHelper
import ru.mybudget.app.utilities.UtilityUserTemplate
import java.util.UUID

class UtilityBillActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_OPEN_PAY = "utility_open_pay"
        private const val STATE_BILL_ID = "utility_bill_id"
    }

    private lateinit var manager: BudgetManager
    private lateinit var adapter: BillDetailAdapter
    private var billId: Int = 0
    private var lastDetail: UtilityBillDetail? = null
    private var photoCount: Int = 0
    private var currentGrandTotal: Double = 0.0
    private var hideZeroLines: Boolean = false
    private var pendingOpenPay: Boolean = false
    private var hasLoadedOnce = false
    private var loadGeneration = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_utility_bill)
        ScreenHeaderHelper.setup(
            this,
            getString(R.string.utility_bill_screen_title),
            getString(R.string.main_icon_utilities),
        )
        billId = intent.getIntExtra(UtilitiesActivity.EXTRA_BILL_ID, 0)
        if (savedInstanceState != null) {
            billId = savedInstanceState.getInt(STATE_BILL_ID, billId)
        }
        pendingOpenPay = intent.getBooleanExtra(EXTRA_OPEN_PAY, false)
        if (billId == 0) {
            finish()
            return
        }
        manager = BudgetManager.getInstance(this)
        adapter = BillDetailAdapter(
            onLineClick = { showEditLineDialog(it) },
            onLineLongClick = { confirmDeleteLine(it) },
        )
        findViewById<RecyclerView>(R.id.billDetailsRecyclerView).apply {
            layoutManager = LinearLayoutManager(this@UtilityBillActivity)
            this.adapter = this@UtilityBillActivity.adapter
        }
        findViewById<View>(R.id.applyMetersButton).setOnClickListener { applyMetersToBill() }
        findViewById<View>(R.id.hideZeroLinesButton).setOnClickListener {
            hideZeroLines = !hideZeroLines
            lastDetail?.let { bindDetail(it) }
        }
        findViewById<View>(R.id.payFromBudgetButton).setOnClickListener { onPayFromBudgetClicked() }
        findViewById<View>(R.id.openPhotosButton).setOnClickListener { openPhotosScreen() }
        findViewById<View>(R.id.billAreaText).setOnClickListener { showEditAreaDialog() }
        loadBill()
    }

    private fun openPhotosScreen() {
        startActivity(
            Intent(this, UtilityBillPhotosActivity::class.java)
                .putExtra(UtilitiesActivity.EXTRA_BILL_ID, billId),
        )
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_BILL_ID, billId)
    }

    override fun onResume() {
        super.onResume()
        if (hasLoadedOnce && billId != 0) {
            loadBill()
        }
    }

    private fun dao() = manager.utilityDao

    private fun loadBill(after: ((UtilityBillDetail) -> Unit)? = null) {
        if (billId == 0) return
        val generation = ++loadGeneration
        lifecycleScope.launch {
            val loaded = runCatching {
                withContext(Dispatchers.IO) {
                    val billDetail = UtilityUserTemplate.loadBillDetail(dao(), billId)
                        ?: return@withContext null
                    val count = dao().getPhotoCountForBill(billId)
                    billDetail to count
                }
            }.getOrElse {
                if (generation != loadGeneration || isFinishing || isDestroyed) return@launch
                Toast.makeText(
                    this@UtilityBillActivity,
                    R.string.utility_bill_load_error,
                    Toast.LENGTH_LONG,
                ).show()
                return@launch
            }
            if (generation != loadGeneration || isFinishing || isDestroyed) return@launch
            if (loaded == null) {
                if (lastDetail == null) {
                    Toast.makeText(
                        this@UtilityBillActivity,
                        R.string.utility_bill_load_error,
                        Toast.LENGTH_LONG,
                    ).show()
                    finish()
                }
                return@launch
            }
            val (billDetail, count) = loaded
            photoCount = count
            hasLoadedOnce = true
            runCatching { bindDetail(billDetail) }.onFailure {
                if (lastDetail == null) {
                    Toast.makeText(
                        this@UtilityBillActivity,
                        R.string.utility_bill_load_error,
                        Toast.LENGTH_LONG,
                    ).show()
                    finish()
                }
                return@launch
            }
            after?.invoke(billDetail)
            if (pendingOpenPay && billDetail.bill.budgetPaidAt == null && billDetail.grandTotal > 0.0) {
                pendingOpenPay = false
                showPayFromBudgetDialog(billDetail)
            }
        }
    }

    private fun bindDetail(detail: UtilityBillDetail) {
        lastDetail = detail
        currentGrandTotal = detail.grandTotal
        findViewById<TextView>(R.id.billPeriodTitle).text =
            UtilityUserTemplate.titlePeriod(detail.bill.year, detail.bill.month)
        findViewById<TextView>(R.id.billGrandTotal).text =
            MoneyFormat.formatRub(detail.grandTotal)
        findViewById<TextView>(R.id.billAreaText).text =
            UtilityUserTemplate.formatAreaLine(this, detail.bill.apartmentArea)
        findViewById<MaterialButton>(R.id.hideZeroLinesButton).setText(
            if (hideZeroLines) R.string.utility_bill_show_zeros else R.string.utility_bill_hide_zeros,
        )
        findViewById<MaterialButton>(R.id.openPhotosButton).text =
            getString(R.string.utility_photos_open, photoCount)
        updatePayButtonState(detail.bill)
        adapter.submit(buildRows(detail, hideZeroLines))
    }

    private fun updatePayButtonState(bill: UtilityBillEntity) {
        val btn = findViewById<MaterialButton>(R.id.payFromBudgetButton)
        if (bill.budgetPaidAt != null) {
            btn.setText(R.string.utility_edit_payment)
            val lines = mutableListOf(
                UtilityUserTemplate.formatAreaLine(this, bill.apartmentArea),
                getString(R.string.utility_bill_lines_hint),
            )
            if (bill.budgetPaymentSummary.isNotBlank()) {
                lines += getString(R.string.utility_paid_from_budget, bill.budgetPaymentSummary)
            }
            if (bill.budgetRemainderSummary.isNotBlank()) {
                lines += getString(R.string.utility_remainder_in_envelope, bill.budgetRemainderSummary)
            }
            findViewById<TextView>(R.id.billAreaText).text = lines.joinToString("\n")
        } else {
            btn.setText(R.string.utility_pay_from_budget)
        }
    }

    private fun showEditAreaDialog() {
        val bill = lastDetail?.bill ?: return
        val input = EditText(this).apply {
            hint = getString(R.string.utility_bill_edit_area_hint)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            if (bill.apartmentArea > 0.0) {
                setText(MoneyFormat.formatQuantity(bill.apartmentArea))
                setSelection(text.length)
            }
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.utility_bill_edit_area_title)
            .setView(padded(input))
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val raw = input.text.toString().trim().replace(',', '.')
                val area = if (raw.isEmpty()) 0.0 else raw.toDoubleOrNull()
                if (area == null || area < 0.0) {
                    Toast.makeText(this, R.string.utility_tariff_invalid, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                lifecycleScope.launch(Dispatchers.IO) {
                    dao().updateBill(bill.copy(apartmentArea = area))
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@UtilityBillActivity, R.string.utility_bill_area_saved, Toast.LENGTH_SHORT).show()
                        loadBill()
                    }
                }
            }
            .setNeutralButton(R.string.utility_tariff_clear) { _, _ ->
                if (bill.apartmentArea <= 0.0) return@setNeutralButton
                lifecycleScope.launch(Dispatchers.IO) {
                    dao().updateBill(bill.copy(apartmentArea = 0.0))
                    withContext(Dispatchers.Main) { loadBill() }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun applyMetersToBill() {
        lifecycleScope.launch(Dispatchers.IO) {
            val result = UtilityMeterBillLinker.applyMeterReadingsToBill(dao(), billId)
            withContext(Dispatchers.Main) {
                showMetersApplyResultDialog(result)
                if (result.updatedLines > 0) loadBill()
            }
        }
    }

    private fun showMetersApplyResultDialog(result: UtilityMeterBillLinker.ApplyResult) {
        if (result.updatedLines == 0 && result.changes.isEmpty()) {
            val message = result.details.firstOrNull()
                ?: getString(R.string.utility_bill_meters_dialog_empty)
            AlertDialog.Builder(this)
                .setTitle(R.string.utility_bill_meters_dialog_title)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }
        val body = buildString {
            append(
                getString(
                    R.string.utility_bill_meters_dialog_summary,
                    result.updatedLines,
                    result.skippedLines,
                ),
            )
            append("\n\n")
            result.changes.forEach { change ->
                append(
                    getString(
                        R.string.utility_bill_meters_line_change,
                        change.name,
                        formatMeterQuantity(change.oldQuantity),
                        formatMeterQuantity(change.newQuantity),
                        formatMeterAmountChange(change.oldAmount, change.newAmount),
                    ),
                )
                append("\n")
            }
        }.trim()
        AlertDialog.Builder(this)
            .setTitle(R.string.utility_bill_meters_dialog_title)
            .setMessage(body)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun formatMeterQuantity(value: Double?): String =
        if (value == null) "—" else MoneyFormat.formatQuantity(value)

    private fun formatMeterAmountChange(oldAmount: Double, newAmount: Double): String =
        "${MoneyFormat.formatRub(oldAmount)} → ${MoneyFormat.formatRub(newAmount)}"

    private fun onPayFromBudgetClicked() {
        val detail = lastDetail ?: return
        if (currentGrandTotal <= 0.0) {
            Toast.makeText(this, R.string.utility_pay_zero_total, Toast.LENGTH_SHORT).show()
            return
        }
        if (UtilityLegacyPaymentHelper.isLegacyPaid(detail.bill)) {
            AlertDialog.Builder(this)
                .setTitle(R.string.utility_edit_payment)
                .setMessage(R.string.utility_clear_legacy_payment_message)
                .setPositiveButton(R.string.utility_pay_from_budget) { _, _ ->
                    clearLegacyAndPayFromBudget(detail)
                }
                .setNeutralButton(R.string.utility_clear_legacy_payment) { _, _ ->
                    clearLegacyPayment(detail.bill)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            return
        }
        if (detail.bill.budgetPaidAt != null) {
            AlertDialog.Builder(this)
                .setTitle(R.string.utility_edit_payment)
                .setMessage(getString(R.string.utility_edit_payment_message, detail.bill.budgetPaymentSummary))
                .setPositiveButton(R.string.utility_edit_payment) { _, _ -> reversePaymentAndEdit(detail) }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        } else {
            showPayFromBudgetDialog(detail)
        }
    }

    private fun reversePaymentAndEdit(detail: UtilityBillDetail) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                val bill = detail.bill
                if (UtilityLegacyPaymentHelper.isLegacyPaid(bill)) {
                    dao().clearBudgetPayment(bill.id)
                } else {
                    val groupId = bill.budgetPaymentGroupId
                    if (!groupId.isNullOrBlank()) {
                        manager.repository.cancelTransactionGroup(groupId)
                    } else if (bill.budgetPaidAt != null) {
                        val propertyName = dao().getPropertyById(bill.propertyId)?.name.orEmpty()
                        val desc = UtilityUserTemplate.paymentDescription(propertyName, bill.year, bill.month)
                        manager.repository.getExpenseTransactionsByDescription(desc).forEach { tx ->
                            manager.repository.cancelTransaction(tx)
                        }
                    }
                    dao().clearBudgetPayment(bill.id)
                    manager.reloadCategoriesFromDatabase()
                }
            }
            loadBill { refreshed ->
                showPayFromBudgetDialog(
                    refreshed.copy(
                        bill = refreshed.bill.copy(
                            budgetPaidAt = null,
                            budgetPaymentSummary = "",
                            budgetRemainderSummary = "",
                            budgetPaymentGroupId = null,
                        ),
                    ),
                )
            }
        }
    }

    private fun clearLegacyAndPayFromBudget(detail: UtilityBillDetail) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                UtilityLegacyPaymentHelper.clearLegacyPaid(dao(), detail.bill)
            }
            loadBill { refreshed ->
                showPayFromBudgetDialog(refreshed)
            }
        }
    }

    private fun clearLegacyPayment(bill: UtilityBillEntity) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                UtilityLegacyPaymentHelper.clearLegacyPaid(dao(), bill)
            }
            loadBill()
        }
    }

    private fun markLegacyPayment(bill: UtilityBillEntity) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                UtilityLegacyPaymentHelper.markBillAsLegacyPaid(this@UtilityBillActivity, dao(), bill)
            }
            Toast.makeText(this@UtilityBillActivity, R.string.utility_pay_legacy_success, Toast.LENGTH_LONG).show()
            loadBill()
        }
    }

    private fun showPayFromBudgetDialog(detail: UtilityBillDetail) {
        lifecycleScope.launch {
            val budgetId = manager.getActiveBudgetId()
            val options = withContext(Dispatchers.IO) {
                UtilityPayCategoryHelper.loadLeafOptions(manager, budgetId)
            }
            if (options.isEmpty()) {
                Toast.makeText(this@UtilityBillActivity, R.string.utility_pay_no_categories, Toast.LENGTH_LONG).show()
                return@launch
            }
            openPayDialog(detail, options, detail.bill.propertyId)
        }
    }

    private fun openPayDialog(
        detail: UtilityBillDetail,
        options: List<UtilityPayCategoryHelper.CategoryOption>,
        propertyId: Int,
    ) {
        val total = currentGrandTotal
        val period = UtilityUserTemplate.formatPeriod(detail.bill.year, detail.bill.month)
        val labels = options.map { it.label }
        val view = layoutInflater.inflate(R.layout.dialog_utility_pay_budget, null)
        val payTotal = view.findViewById<TextView>(R.id.payTotalText)
        val splitPreview = view.findViewById<TextView>(R.id.paySplitPreview)
        val primarySpinner = view.findViewById<Spinner>(R.id.payPrimarySpinner)
        val primaryBalance = view.findViewById<TextView>(R.id.payPrimaryBalance)
        val shortfallPanel = view.findViewById<LinearLayout>(R.id.payShortfallPanel)
        val shortfallText = view.findViewById<TextView>(R.id.payShortfallText)
        val extraSpinner = view.findViewById<Spinner>(R.id.payExtraSpinner)
        val extraBalance = view.findViewById<TextView>(R.id.payExtraBalance)
        payTotal.text = getString(R.string.utility_pay_total, MoneyFormat.format(total))
        val spinnerAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        primarySpinner.adapter = spinnerAdapter
        extraSpinner.adapter = spinnerAdapter
        fun refreshSplitUi() {
            val primary = options[primarySpinner.selectedItemPosition]
            val bal1 = primary.category.currentBalance
            val part1 = if (bal1 <= 0.0) 0.0 else minOf(bal1, total)
            val shortfall = maxOf(0.0, total - part1)
            primaryBalance.text = getString(R.string.utility_pay_balance, MoneyFormat.format(bal1))
            if (shortfall <= 0.01) {
                shortfallPanel.visibility = View.GONE
                splitPreview.text = getString(R.string.utility_pay_single, MoneyFormat.format(total))
            } else {
                shortfallPanel.visibility = View.VISIBLE
                val extra = options[extraSpinner.selectedItemPosition]
                shortfallText.text = getString(
                    R.string.utility_pay_shortfall,
                    MoneyFormat.format(part1),
                    MoneyFormat.format(shortfall),
                )
                extraBalance.text = getString(R.string.utility_pay_balance, MoneyFormat.format(extra.category.currentBalance))
                splitPreview.text = shortfallText.text
            }
        }
        val listener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = refreshSplitUi()
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        primarySpinner.onItemSelectedListener = listener
        extraSpinner.onItemSelectedListener = listener
        val primaryIndex = UtilityPayCategoryHelper.primarySpinnerIndex(options, this, propertyId)
        primarySpinner.setSelection(primaryIndex)
        extraSpinner.setSelection(UtilityPayCategoryHelper.extraSpinnerIndex(options, this, propertyId, primaryIndex))
        refreshSplitUi()
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.utility_pay_title)
            .setView(view)
            .setPositiveButton(R.string.utility_pay_from_budget, null)
            .setNeutralButton(R.string.utility_pay_legacy, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                dialog.dismiss()
                markLegacyPayment(detail.bill)
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val primary = options[primarySpinner.selectedItemPosition]
                val part1 = if (primary.category.currentBalance <= 0.0) {
                    0.0
                } else {
                    minOf(primary.category.currentBalance, total)
                }
                val shortfall = maxOf(0.0, total - part1)
                val parts = mutableListOf(primary.category to part1)
                var extraCategory: BudgetCategory? = null
                if (shortfall > 0.01) {
                    val extra = options[extraSpinner.selectedItemPosition]
                    if (extra.category.id == primary.category.id) {
                        Toast.makeText(this, R.string.utility_pay_same_category, Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    extraCategory = extra.category
                    parts += extra.category to shortfall
                }
                UtilityPayCategoryHelper.rememberSelection(this, propertyId, primary.category, extraCategory)
                dialog.dismiss()
                executeBudgetPayment(detail.bill, period, parts)
            }
        }
        dialog.show()
    }

    private fun executeBudgetPayment(
        bill: UtilityBillEntity,
        periodLabel: String,
        parts: List<Pair<BudgetCategory, Double>>,
    ) {
        lifecycleScope.launch {
            val summary = withContext(Dispatchers.IO) {
                val propertyName = dao().getPropertyById(bill.propertyId)?.name.orEmpty()
                val description = UtilityUserTemplate.paymentDescription(propertyName, bill.year, bill.month)
                val summaryParts = mutableListOf<String>()
                val items = mutableListOf<Pair<Int, Double>>()
                for ((cat, amount) in parts) {
                    if (amount <= 0.0) continue
                    val rounded = MoneyFormat.roundMoney(amount)
                    items += cat.id to rounded
                    summaryParts += "${cat.name}: ${MoneyFormat.formatRub(rounded)}"
                }
                if (items.isEmpty()) return@withContext null
                val groupId = UUID.randomUUID().toString()
                manager.repository.applyTransactionGroup(items, "expense", description, groupId)
                manager.reloadCategoriesFromDatabase()
                val updated = manager.getCategories()
                val remainderParts = parts.map { (cat, _) ->
                    val balance = updated.firstOrNull { it.id == cat.id }?.currentBalance ?: 0.0
                    "${cat.name}: ${MoneyFormat.formatRub(balance)}"
                }
                val summaryText = summaryParts.joinToString("; ")
                dao().updateBill(
                    bill.copy(
                        budgetPaidAt = System.currentTimeMillis(),
                        budgetPaymentSummary = summaryText,
                        budgetRemainderSummary = remainderParts.joinToString("; "),
                        budgetPaymentGroupId = groupId,
                    ),
                )
                summaryText
            }
            if (summary != null) {
                Toast.makeText(this@UtilityBillActivity, getString(R.string.utility_pay_success, summary), Toast.LENGTH_LONG).show()
            }
            loadBill()
        }
    }

    private fun buildRows(detail: UtilityBillDetail, hideZeros: Boolean): List<BillRow> {
        val rows = mutableListOf<BillRow>()
        for (section in detail.sections) {
            val lines = section.lines.filter { line ->
                !hideZeros || line.amount != 0.0 || line.quantity != null
            }
            if (lines.isEmpty()) continue
            rows += BillRow.SectionHeader(section.section.name, lines.sumOf { it.amount })
            var lastGroup = ""
            for (line in lines) {
                if (line.groupLabel.isNotBlank() && line.groupLabel != lastGroup) {
                    rows += BillRow.GroupLabel(line.groupLabel)
                    lastGroup = line.groupLabel
                }
                rows += BillRow.Line(line)
            }
        }
        return rows
    }

    private fun showEditLineDialog(line: UtilityLineItemEntity) {
        val view = layoutInflater.inflate(R.layout.dialog_edit_utility_line, null)
        val qtyInput = view.findViewById<EditText>(R.id.quantityInput)
        val tariffInput = view.findViewById<EditText>(R.id.tariffInput)
        val amountInput = view.findViewById<EditText>(R.id.amountInput)
        line.quantity?.let { qtyInput.setText(MoneyFormat.formatQuantity(it)) }
        line.tariff?.let { tariffInput.setText(MoneyFormat.format(it)) }
        amountInput.setText(MoneyFormat.format(line.amount))
        var updatingAmount = false
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (updatingAmount) return
                val computed = UtilityUserTemplate.computedAmount(
                    MoneyFormat.parseQuantity(qtyInput.text),
                    MoneyFormat.parse(tariffInput.text),
                ) ?: return
                updatingAmount = true
                amountInput.setText(MoneyFormat.format(computed))
                amountInput.setSelection(amountInput.text.length)
                updatingAmount = false
            }
        }
        qtyInput.addTextChangedListener(watcher)
        tariffInput.addTextChangedListener(watcher)
        AlertDialog.Builder(this)
            .setTitle(line.name)
            .setView(view)
            .setPositiveButton(R.string.save) { _, _ ->
                val qty = MoneyFormat.parseQuantity(qtyInput.text)
                val tariff = MoneyFormat.parse(tariffInput.text)
                var amount = MoneyFormat.parse(amountInput.text) ?: line.amount
                val computed = UtilityUserTemplate.computedAmount(qty, tariff)
                if (computed != null && qty != null && tariff != null) amount = computed
                val updated = line.copy(quantity = qty, tariff = tariff, amount = MoneyFormat.roundMoney(amount))
                lifecycleScope.launch(Dispatchers.IO) {
                    dao().updateLineItem(updated)
                    withContext(Dispatchers.Main) { loadBill() }
                }
            }
            .setNeutralButton(R.string.delete) { _, _ -> confirmDeleteLine(line) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmDeleteLine(line: UtilityLineItemEntity) {
        AlertDialog.Builder(this)
            .setTitle(R.string.utility_bill_delete_line_title)
            .setMessage(getString(R.string.utility_bill_delete_line_msg, line.name))
            .setPositiveButton(R.string.delete) { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    dao().deleteLineItemById(line.id)
                    withContext(Dispatchers.Main) { loadBill() }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun padded(child: View): View {
        val pad = (24 * resources.displayMetrics.density).toInt()
        return android.widget.FrameLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(child, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    private sealed class BillRow {
        data class SectionHeader(val title: String, val total: Double) : BillRow()
        data class GroupLabel(val label: String) : BillRow()
        data class Line(val item: UtilityLineItemEntity) : BillRow()
    }

    private class BillDetailAdapter(
        private val onLineClick: (UtilityLineItemEntity) -> Unit,
        private val onLineLongClick: (UtilityLineItemEntity) -> Unit,
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private var rows: List<BillRow> = emptyList()

        fun submit(list: List<BillRow>) {
            rows = list
            notifyDataSetChanged()
        }

        override fun getItemViewType(position: Int): Int = when (rows[position]) {
            is BillRow.SectionHeader -> 0
            is BillRow.GroupLabel -> 1
            is BillRow.Line -> 2
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inf = LayoutInflater.from(parent.context)
            return when (viewType) {
                0 -> SectionHolder(inf.inflate(R.layout.item_utility_section_header, parent, false))
                1 -> GroupHolder(inf.inflate(R.layout.item_utility_group_label, parent, false))
                else -> LineHolder(inf.inflate(R.layout.item_utility_line, parent, false))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val row = rows[position]) {
                is BillRow.SectionHeader -> {
                    val h = holder as SectionHolder
                    h.title.text = row.title
                    h.total.text = MoneyFormat.formatRub(row.total)
                }
                is BillRow.GroupLabel -> (holder as GroupHolder).label.text = row.label
                is BillRow.Line -> {
                    val h = holder as LineHolder
                    val line = row.item
                    h.name.text = line.name
                    h.amount.text = MoneyFormat.formatRub(line.amount)
                    val qty = line.quantity
                    val tariff = line.tariff
                    h.qtyTariff.text = when {
                        qty != null && tariff != null ->
                            "кол-во ${MoneyFormat.formatQuantity(qty)} × тариф ${MoneyFormat.format(tariff)}"
                        qty != null -> "кол-во ${MoneyFormat.formatQuantity(qty)}"
                        else -> ""
                    }
                    h.itemView.setOnClickListener { onLineClick(line) }
                    h.itemView.setOnLongClickListener {
                        onLineLongClick(line)
                        true
                    }
                }
            }
        }

        override fun getItemCount(): Int = rows.size

        class SectionHolder(v: View) : RecyclerView.ViewHolder(v) {
            val title: TextView = v.findViewById(R.id.sectionTitle)
            val total: TextView = v.findViewById(R.id.sectionTotal)
        }

        class GroupHolder(v: View) : RecyclerView.ViewHolder(v) {
            val label: TextView = v.findViewById(R.id.groupLabel)
        }

        class LineHolder(v: View) : RecyclerView.ViewHolder(v) {
            val name: TextView = v.findViewById(R.id.lineName)
            val qtyTariff: TextView = v.findViewById(R.id.lineQtyTariff)
            val amount: TextView = v.findViewById(R.id.lineAmount)
        }
    }
}
