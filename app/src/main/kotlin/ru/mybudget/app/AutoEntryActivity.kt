package ru.mybudget.app

import android.os.Bundle
import androidx.activity.viewModels
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import androidx.activity.OnBackPressedCallback
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.mybudget.app.auto.*
import ru.mybudget.app.data.AutoRepository
import ru.mybudget.app.data.BudgetDatabase
import java.time.LocalDate
import java.time.format.DateTimeFormatter

class AutoEntryActivity : AppCompatActivity() {
    private lateinit var repository: AutoRepository
    private lateinit var manager: BudgetManager
    private lateinit var type: AutoEntryType
    private var vehicleId = 0
    private var saving = false
    private val saveModel: AutoEntrySaveViewModel by viewModels()
    private var requestId = java.util.UUID.randomUUID().toString()
    private var setFormBusy: (Boolean) -> Unit = {}
    private var ready = false
    private var restored: Bundle? = null
    private val itemRows = mutableListOf<Pair<EditText, EditText>>()
    private val fields = listOf(R.id.dateInput, R.id.endDateInput, R.id.mileageInput, R.id.litersInput,
        R.id.priceInput, R.id.descInput, R.id.amountInput)
    private val dateFormat = DateTimeFormatter.ofPattern("dd.MM.uuuu").withResolverStyle(java.time.format.ResolverStyle.STRICT)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_auto_entry)
        restored = savedInstanceState
        requestId = savedInstanceState?.getString("auto_save_request") ?: requestId
        manager = BudgetManager.getInstance(this)
        repository = AutoRepository(BudgetDatabase.getInstance(this))
        type = runCatching { AutoEntryType.valueOf(intent.getStringExtra(EXTRA_TYPE).orEmpty()) }.getOrElse { finish(); return }
        vehicleId = intent.getIntExtra(EXTRA_VEHICLE, 0)
        val id = intent.getIntExtra(EXTRA_ID, 0)
        val titles = when (type) {
            AutoEntryType.SERVICE -> R.string.auto_editor_service
            AutoEntryType.REPAIR -> R.string.auto_editor_repair
            AutoEntryType.FUEL -> R.string.auto_editor_fuel
            AutoEntryType.INSURANCE -> R.string.auto_editor_insurance
        }
        ScreenHeaderHelper.setup(this, getString(if (id > 0) R.string.auto_editor_edit else R.string.auto_editor_add, getString(titles)))
        findViewById<Button>(R.id.saveAutoEntryButton).apply {
            isEnabled = false
            setText(if (id > 0) R.string.auto_editor_save_changes else R.string.save)
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { if (!saving) finish() }
        })
        lifecycleScope.launch {
            try {
                val existing = withContext(Dispatchers.IO) {
                    if (id > 0) repository.getEntries(vehicleId).firstOrNull { it.type == type && it.id == id } else null
                }
                require(id == 0 || existing != null) { getString(R.string.auto_editor_missing) }
                val vehicle = withContext(Dispatchers.IO) { repository.getAllVehicles().firstOrNull { it.id == vehicleId } }
                requireNotNull(vehicle) { getString(R.string.auto_editor_missing) }
                require(!vehicle.isArchived && existing?.isArchived != true) { getString(R.string.auto_archive_read_only) }
                findViewById<TextView>(R.id.autoEditorVehicle).text = vehicle.name
                val cats = manager.getCategoriesAsync().filter { it.isActive && !manager.hasSubcategories(it.id) }
                bindForm(existing, cats)
                ready = true
                findViewById<View>(R.id.autoEntryForm).visibility = View.VISIBLE
                findViewById<Button>(R.id.saveAutoEntryButton).isEnabled = true
                saveModel.state.collect { state ->
                    when (state) {
                        AutoEntrySaveViewModel.State.Idle -> setFormBusy(false)
                        AutoEntrySaveViewModel.State.Saving -> setFormBusy(true)
                        is AutoEntrySaveViewModel.State.Saved -> { setFormBusy(true); finish() }
                        is AutoEntrySaveViewModel.State.Failed -> {
                            setFormBusy(false)
                            Toast.makeText(this@AutoEntryActivity, getString(R.string.auto_save_failed, state.message), Toast.LENGTH_LONG).show()
                        }
                    }
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled
            } catch (e: Exception) {
                Toast.makeText(this@AutoEntryActivity, getString(R.string.auto_save_failed, e.message.orEmpty()), Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    private fun bindForm(existing: AutoEntry?, cats: List<BudgetCategory>) {
        val layout = findViewById<View>(R.id.autoEntryForm)
        val dateInput = layout.findViewById<EditText>(R.id.dateInput)
        val endDateInput = layout.findViewById<EditText>(R.id.endDateInput)
        val mileageInput = layout.findViewById<EditText>(R.id.mileageInput)
        val litersInput = layout.findViewById<EditText>(R.id.litersInput)
        val priceInput = layout.findViewById<EditText>(R.id.priceInput)
        val descInput = layout.findViewById<EditText>(R.id.descInput)
        val amountInput = layout.findViewById<EditText>(R.id.amountInput)
        val categorySpinner = layout.findViewById<Spinner>(R.id.categorySpinner)
        val paymentSpinner = layout.findViewById<Spinner>(R.id.paymentMethodSpinner)
        val itemsContainer = layout.findViewById<LinearLayout>(R.id.itemsContainer)
        val addItemButton = layout.findViewById<Button>(R.id.addItemButton)

        categoryIds = cats.map { it.id }
        fun recalcTotal() {
            val hasItems = itemRows.any { (name, price) -> name.text.isNotBlank() || price.text.isNotBlank() }
            val fuel = type == AutoEntryType.FUEL
            val calculated = hasItems && (type == AutoEntryType.SERVICE || type == AutoEntryType.REPAIR)
            amountInput.isEnabled = !fuel && !calculated
            layout.findViewById<TextView>(R.id.autoAmountExplanation).setText(
                if (fuel) R.string.auto_editor_fuel_total else if (calculated) R.string.auto_editor_items_total else if (type == AutoEntryType.INSURANCE) R.string.auto_editor_enter_total else R.string.auto_editor_manual_total)
            val total = when {
                fuel -> (MoneyFormat.parseQuantity(litersInput.text) ?: 0.0) * (MoneyFormat.parseQuantity(priceInput.text) ?: 0.0)
                calculated -> itemRows.sumOf { (_, price) -> MoneyFormat.parse(price.text)?.takeIf { it.isFinite() } ?: 0.0 }
                else -> MoneyFormat.parse(amountInput.text) ?: 0.0
            }
            if (fuel || calculated) {
                val text = MoneyFormat.format(total)
                if (amountInput.text.toString() != text) amountInput.setText(text)
            }
            findViewById<TextView>(R.id.autoEditorTotal).text = MoneyFormat.formatRub(total)
        }
        fun addRow(name: String = "", price: String = "") {
            val row = layoutInflater.inflate(R.layout.item_auto_editor_work, itemsContainer, false)
            val nameEdit = row.findViewById<EditText>(R.id.autoWorkName)
            val priceEdit = row.findViewById<EditText>(R.id.autoWorkPrice)
            nameEdit.setText(name); priceEdit.setText(price)
            itemRows.add(nameEdit to priceEdit)
            itemsContainer.addView(row)
            nameEdit.doAfterTextChanged { recalcTotal() }
            priceEdit.doAfterTextChanged { recalcTotal() }
            row.findViewById<View>(R.id.autoWorkRemove).setOnClickListener {
                itemRows.remove(nameEdit to priceEdit); itemsContainer.removeView(row); recalcTotal()
            }
        }
        val multiItem = type == AutoEntryType.SERVICE || type == AutoEntryType.REPAIR
        findViewById<View>(R.id.autoWorksSection).visibility = if (multiItem) View.VISIBLE else View.GONE
        itemsContainer.visibility = if (multiItem) View.VISIBLE else View.GONE
        addItemButton.visibility = if (multiItem) View.VISIBLE else View.GONE
        addItemButton.setOnClickListener { addRow(); recalcTotal() }
        if (existing != null) {
            dateInput.setText(formatDay(existing.dateEpochDay))
            amountInput.setText(MoneyFormat.format(existing.amount))
            existing.mileageKm?.let { mileageInput.setText(it.toString()) }
            if (multiItem) {
                val (comments, itemLines) = existing.description.lines().partition { !isItemLine(it) }
                descInput.setText(comments.joinToString("\n").trim())
                itemLines.forEach { line ->
                    val (name, price) = splitItemLine(line)
                    addRow(name, MoneyFormat.format(price))
                }
            } else descInput.setText(existing.description)
            if (type == AutoEntryType.INSURANCE) existing.endDateEpochDay?.let { endDateInput.setText(formatDay(it)) }
            if (type == AutoEntryType.FUEL) {
                litersInput.setText(MoneyFormat.formatQuantity(existing.liters))
                priceInput.setText(MoneyFormat.formatQuantity(existing.pricePerLiter))
            }
        } else {
            dateInput.setText(formatDay(LocalDate.now().toEpochDay()))
            if (type == AutoEntryType.INSURANCE) endDateInput.setText(formatDay(LocalDate.now().plusYears(1).toEpochDay()))
        }
        categorySpinner.adapter = ArrayAdapter(
            this,
            R.layout.item_operation_spinner,
            listOf(getString(R.string.auto_choose_category)) + cats.map { category ->
                val parent = manager.getCategories().firstOrNull { it.id == category.parentId }?.name
                if (parent.isNullOrBlank()) category.name else "$parent → ${category.name}"
            },
        )
        (categorySpinner.adapter as ArrayAdapter<*>).setDropDownViewResource(R.layout.item_operation_spinner_dropdown)
        val saved = existing?.categoryId?.toInt()
            ?: AutoPreferences.getDefaultCategoryId(this, type.name.lowercase())
        val savedIndex = cats.indexOfFirst { it.id == saved }
        categorySpinner.setSelection(if (savedIndex >= 0) savedIndex + 1 else 0)

        paymentSpinner.adapter = ArrayAdapter(this, R.layout.item_operation_spinner,
            listOf(getString(R.string.auto_unpaid), getString(R.string.auto_payment_budget),
                getString(R.string.auto_payment_cash)))
        (paymentSpinner.adapter as ArrayAdapter<*>).setDropDownViewResource(R.layout.item_operation_spinner_dropdown)
        paymentSpinner.setSelection(AutoPaymentMethod.options.indexOf(
            existing?.paymentMethod ?: AutoPaymentMethod.UNPAID).coerceAtLeast(0))
        fun updatePaymentFields() {
            val visible = paymentSpinner.selectedItemPosition == 1
            categorySpinner.visibility = if (visible) View.VISIBLE else View.GONE
            layout.findViewById<View>(R.id.autoCategoryLabel).visibility = if (visible) View.VISIBLE else View.GONE
        }
        paymentSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                updatePaymentFields()
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }
        updatePaymentFields()
        fun showGroup(id: Int, visible: Boolean) { findViewById<View>(id).visibility = if (visible) View.VISIBLE else View.GONE }
        showGroup(R.id.autoEndDateGroup, type == AutoEntryType.INSURANCE)
        showGroup(R.id.autoMileageGroup, type != AutoEntryType.INSURANCE)
        showGroup(R.id.autoFuelGroup, type == AutoEntryType.FUEL)
        showGroup(R.id.autoCommentGroup, multiItem)
        restored?.takeIf { it.getBoolean("editor_ready") }?.let { state ->
            fields.forEach { id -> state.getString("field_$id")?.let { findViewById<EditText>(id).setText(it) } }
            itemsContainer.removeAllViews(); itemRows.clear()
            val names = state.getStringArray("work_names").orEmpty()
            val prices = state.getStringArray("work_prices").orEmpty()
            names.forEachIndexed { i, name -> addRow(name, prices.getOrNull(i).orEmpty()) }
            categorySpinner.setSelection((cats.indexOfFirst { it.id == state.getInt("editor_category", -1) } + 1).coerceAtLeast(0))
            paymentSpinner.setSelection(state.getInt("editor_payment", 0))
        }
        if (multiItem && itemRows.isEmpty() && existing == null && restored == null) addRow()
        amountInput.doAfterTextChanged { recalcTotal() }
        litersInput.doAfterTextChanged { recalcTotal() }
        priceInput.doAfterTextChanged { recalcTotal() }
        fun datePicker(input: EditText) {
            val day = parseDate(input.text.toString())?.let { LocalDate.ofEpochDay(it) } ?: LocalDate.now()
            android.app.DatePickerDialog(this, { _, year, month, date ->
                input.setText(LocalDate.of(year, month + 1, date).format(dateFormat))
            }, day.year, day.monthValue - 1, day.dayOfMonth).show()
        }
        dateInput.setOnClickListener { datePicker(dateInput) }
        endDateInput.setOnClickListener { datePicker(endDateInput) }
        recalcTotal()
        setFormBusy = { busy ->
                    saving = busy
                    findViewById<Button>(R.id.saveAutoEntryButton).isEnabled = !busy
                    // Prevent changes to the submitted draft while payment confirmation is open.
                    fun enable(view: View) {
                        view.isEnabled = !busy
                        if (view is android.view.ViewGroup) for (i in 0 until view.childCount) enable(view.getChildAt(i))
                    }
                    enable(layout)
                    if (!busy) recalcTotal()

        }
        findViewById<Button>(R.id.saveAutoEntryButton).setOnClickListener {
            if (saving || !ready) return@setOnClickListener
            val items = mutableListOf<Pair<String, Double>>()
            for ((name, price) in itemRows) {
                val label = name.text.toString().trim()
                val text = price.text.toString().trim()
                if (label.isEmpty() && text.isEmpty()) continue
                val sum = MoneyFormat.parse(text)
                if (label.isEmpty() || sum == null || !sum.isFinite() || sum < 0.0) {
                    price.error = getString(R.string.auto_invalid_item)
                    name.error = if (label.isEmpty()) getString(R.string.auto_item_name) else null
                    toast(R.string.auto_invalid_item); return@setOnClickListener
                }
                items.add(label to sum)
            }
            submitEntry(type, existing, cats, categorySpinner, paymentSpinner, dateInput, endDateInput,
                mileageInput, litersInput, priceInput, descInput, amountInput, items,
                onBusy = setFormBusy)
        }
    }

    private fun submitEntry(
        type: AutoEntryType, existing: AutoEntry?, cats: List<BudgetCategory>,
        categorySpinner: Spinner, paymentSpinner: Spinner, dateInput: EditText,
        endDateInput: EditText, mileageInput: EditText, litersInput: EditText,
        priceInput: EditText, descInput: EditText, amountInput: EditText,
        items: List<Pair<String, Double>>, onBusy: (Boolean) -> Unit,
    ) {
        val date = parseDate(dateInput.text.toString()) ?: return toast(R.string.auto_bad_date)
        val method = AutoPaymentMethod.options.getOrNull(paymentSpinner.selectedItemPosition) ?: return
        val category = cats.getOrNull(categorySpinner.selectedItemPosition - 1)
        if (method == AutoPaymentMethod.BUDGET && category == null) return toast(R.string.auto_no_categories)
        val mileageText = mileageInput.text.toString().trim()
        val mileage = mileageText.toIntOrNull()
        if (type != AutoEntryType.INSURANCE && mileageText.isNotEmpty() && (mileage == null || mileage < 0)) {
            return toast(R.string.auto_invalid_mileage)
        }
        val end = if (type == AutoEntryType.INSURANCE) {
            parseDate(endDateInput.text.toString()) ?: return toast(R.string.auto_bad_date)
        } else null
        if (end != null && end < date) return toast(R.string.auto_invalid_insurance_dates)
        val liters = if (type == AutoEntryType.FUEL) MoneyFormat.parseQuantity(litersInput.text) else 0.0
        val price = if (type == AutoEntryType.FUEL) MoneyFormat.parseQuantity(priceInput.text) else 0.0
        if (liters == null || price == null || !liters.isFinite() || !price.isFinite() ||
            (type == AutoEntryType.FUEL && (liters <= 0.0 || price < 0.0))) {
            return toast(R.string.auto_bad_amount)
        }
        var description = descInput.text.toString().trim()
        val amount = when {
            type == AutoEntryType.FUEL -> MoneyFormat.roundMoney(liters * price)
            items.isNotEmpty() -> MoneyFormat.roundMoney(items.sumOf { it.second })
            else -> MoneyFormat.parse(amountInput.text) ?: return toast(R.string.auto_bad_amount)
        }
        if (!amount.isFinite() || amount < 0.0 || (method != AutoPaymentMethod.UNPAID && amount <= 0.0)) {
            return toast(R.string.auto_bad_amount)
        }
        if (items.isNotEmpty()) {
            val lines = items.joinToString("\n") { (name, sum) -> "$name — ${MoneyFormat.formatRub(sum)}" }
            description = listOf(description, lines).filter { it.isNotBlank() }.joinToString("\n")
        }
        val old = existing
        val entry = AutoEntry(type = type, id = old?.id ?: 0,
            vehicleId = old?.vehicleId ?: vehicleId, dateEpochDay = date, amount = amount,
            categoryId = if (method == AutoPaymentMethod.BUDGET) category!!.id.toLong() else -1,
            paymentMethod = method, description = description, mileageKm = mileage,
            endDateEpochDay = end, liters = liters, pricePerLiter = price,
            createdAt = old?.createdAt ?: System.currentTimeMillis())
        onBusy(true)
        lifecycleScope.launch {
            try {
                if (method == AutoPaymentMethod.BUDGET) {
                    val available = withContext(Dispatchers.IO) {
                        val credit = old?.paidTransactionId?.let {
                            BudgetDatabase.getInstance(this@AutoEntryActivity).budgetDao().getTransactionById(it.toInt())
                        }?.takeIf { it.categoryId == category!!.id }?.let {
                            if (it.type == "expense") it.amount else -it.amount
                        } ?: 0.0
                        repository.getCategoryBalance(category!!.id) + credit
                    }
                    if (available < amount && !confirmOverspend(amount - available)) return@launch
                }
                saveModel.save(repository, entry, requestId)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Toast.makeText(this@AutoEntryActivity, getString(R.string.auto_save_failed, e.message.orEmpty()),
                    Toast.LENGTH_LONG).show()
            } finally {
                if (saveModel.state.value != AutoEntrySaveViewModel.State.Saving &&
                    saveModel.state.value !is AutoEntrySaveViewModel.State.Saved) onBusy(false)
            }
        }
    }

    private suspend fun confirmOverspend(shortage: Double): Boolean =
        kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
            val dialog = AlertDialog.Builder(this)
                .setTitle(R.string.auto_negative_title)
                .setMessage(getString(R.string.auto_negative_message, MoneyFormat.formatRub(shortage)))
                .setPositiveButton(R.string.save) { _, _ ->
                    if (continuation.isActive) continuation.resumeWith(Result.success(true))
                }
                .setNegativeButton(android.R.string.cancel) { _, _ ->
                    if (continuation.isActive) continuation.resumeWith(Result.success(false))
                }
                .setOnCancelListener {
                    if (continuation.isActive) continuation.resumeWith(Result.success(false))
                }
                .show()
            continuation.invokeOnCancellation { runOnUiThread { dialog.dismiss() } }
        }

    private fun parseItemPrice(text: String): Double? =
        MoneyFormat.parseQuantity(text.trim().removeSuffix("₽"))

    private fun isItemLine(line: String): Boolean {
        val idx = line.lastIndexOf(" — ")
        if (idx <= 0) return false
        return parseItemPrice(line.substring(idx + 3)) != null
    }

    private fun splitItemLine(line: String): Pair<String, Double> {
        val idx = line.lastIndexOf(" — ")
        if (idx <= 0) return line.trim() to 0.0
        val price = parseItemPrice(line.substring(idx + 3)) ?: 0.0
        return line.substring(0, idx).trim() to price
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("auto_save_request", requestId)
        outState.putBoolean("editor_ready", ready)
        if (ready) {
            fields.forEach { id -> outState.putString("field_$id", findViewById<EditText>(id).text.toString()) }
            outState.putStringArray("work_names", itemRows.map { it.first.text.toString() }.toTypedArray())
            outState.putStringArray("work_prices", itemRows.map { it.second.text.toString() }.toTypedArray())
            outState.putInt("editor_category", selectedCategoryId())
            outState.putInt("editor_payment", findViewById<Spinner>(R.id.paymentMethodSpinner).selectedItemPosition)
        }
        super.onSaveInstanceState(outState)
    }
    private var categoryIds: List<Int> = emptyList()
    private fun selectedCategoryId() = categoryIds.getOrNull(findViewById<Spinner>(R.id.categorySpinner).selectedItemPosition - 1) ?: -1
    private fun formatDay(day: Long) = LocalDate.ofEpochDay(day).format(dateFormat)
    private fun parseDate(text: String): Long? = runCatching { LocalDate.parse(text.trim(), dateFormat).toEpochDay() }.getOrNull()
    private fun toast(res: Int) { Toast.makeText(this, res, Toast.LENGTH_SHORT).show() }
    companion object {
        const val EXTRA_TYPE = "auto_entry_type"
        const val EXTRA_ID = "auto_entry_id"
        const val EXTRA_VEHICLE = "auto_entry_vehicle"
    }
}
