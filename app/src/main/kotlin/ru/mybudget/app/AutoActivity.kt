package ru.mybudget.app

import android.os.Bundle
import android.content.Intent
import ru.mybudget.app.auto.AutoEntry
import ru.mybudget.app.auto.AutoEntryType
import ru.mybudget.app.auto.AutoPaymentMethod
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import android.widget.PopupMenu
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.mybudget.app.auto.AutoPreferences
import ru.mybudget.app.data.AutoRepository
import ru.mybudget.app.data.BudgetDatabase
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

class AutoActivity : AppCompatActivity() {

    private enum class EntryType(val key: String, val labelRes: Int) {
        SERVICE("service", R.string.auto_section_service),
        INSURANCE("insurance", R.string.auto_section_insurance),
        FUEL("fuel", R.string.auto_section_fuel),
        REPAIR("repair", R.string.auto_section_repair),
    }

    private sealed class Record {
        abstract val dateEpochDay: Long
        abstract val amount: Double
        abstract val title: String
        abstract val subtitle: String
        abstract val paid: Boolean
        abstract val type: EntryType
        val paymentMethod: String get() = when (this) {
            is Service -> AutoPaymentMethod.resolve(log.paymentMethod, log.paidTransactionId)
            is Insurance -> AutoPaymentMethod.resolve(item.paymentMethod, item.paidTransactionId)
            is Fuel -> AutoPaymentMethod.resolve(log.paymentMethod, log.paidTransactionId)
            is Repair -> AutoPaymentMethod.resolve(log.paymentMethod, log.paidTransactionId)
        }

        data class Service(val log: ru.mybudget.app.data.VehicleServiceLogEntity) : Record() {
            override val dateEpochDay = log.dateEpochDay
            override val amount = log.amount
            override val title = log.workDescription.ifBlank { "" }
            override val subtitle = buildString {
                log.mileageKm?.let { append(it).append(" км") }
            }
            override val paid = AutoPaymentMethod.resolve(log.paymentMethod, log.paidTransactionId) != AutoPaymentMethod.UNPAID
            override val type = EntryType.SERVICE
        }

        data class Insurance(val item: ru.mybudget.app.data.VehicleInsuranceEntity) : Record() {
            override val dateEpochDay = item.startDateEpochDay
            override val amount = item.amount
            override val title = "ОСАГО"
            override val subtitle = buildString {
                append("до ").append(formatDay(item.endDateEpochDay))
                val days = AutoRepository.daysUntil(item.endDateEpochDay, todayEpochDay())
                if (days >= 0) append(" · ").append(days).append(" дн.")
                else append(" · истекла")
            }
            override val paid = AutoPaymentMethod.resolve(item.paymentMethod, item.paidTransactionId) != AutoPaymentMethod.UNPAID
            override val type = EntryType.INSURANCE
        }

        data class Fuel(val log: ru.mybudget.app.data.VehicleFuelLogEntity) : Record() {
            override val dateEpochDay = log.dateEpochDay
            override val amount = log.amount
            override val title = "Бензин"
            override val subtitle = buildString {
                append(MoneyFormat.formatQuantity(log.liters)).append(" л · ")
                append(MoneyFormat.formatQuantity(log.pricePerLiter)).append(" ₽/л")
                log.mileageKm?.let { append(" · ").append(it).append(" км") }
            }
            override val paid = AutoPaymentMethod.resolve(log.paymentMethod, log.paidTransactionId) != AutoPaymentMethod.UNPAID
            override val type = EntryType.FUEL
        }

        data class Repair(val log: ru.mybudget.app.data.VehicleRepairEntity) : Record() {
            override val dateEpochDay = log.dateEpochDay
            override val amount = log.amount
            override val title = log.orderDescription.ifBlank { "Ремонт" }
            override val subtitle = buildString {
                log.mileageKm?.let { append(it).append(" км") }
            }
            override val paid = AutoPaymentMethod.resolve(log.paymentMethod, log.paidTransactionId) != AutoPaymentMethod.UNPAID
            override val type = EntryType.REPAIR
        }
    }

    private lateinit var repository: AutoRepository
    private lateinit var manager: BudgetManager
    private var vehicleId: Int = 0
    private var vehicles: List<ru.mybudget.app.data.VehicleEntity> = emptyList()
    private var records: List<Record> = emptyList()
    private var filter: EntryType? = null
    private var refreshJob: kotlinx.coroutines.Job? = null
    private var loading = true

    private lateinit var vehicleNameText: TextView
    private lateinit var fuelStatsText: TextView
    private lateinit var recycler: RecyclerView
    private lateinit var emptyState: TextView

    private val adapter = RecordAdapter(
        onClick = { showRecordDialog(it) },
    )

    private val dateFormat = DateTimeFormatter.ofPattern("dd.MM.uuuu")
        .withResolverStyle(java.time.format.ResolverStyle.STRICT)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_auto)
        ScreenHeaderHelper.setup(this, getString(R.string.main_menu_auto))
        vehicleId = savedInstanceState?.getInt("auto_vehicle", 0) ?: 0
        filter = EntryType.entries.firstOrNull { it.key == savedInstanceState?.getString("auto_filter") }
        ScreenHeaderHelper.bindAction(this, R.drawable.ic_nav_more, R.string.design_auto_manage) {
            PopupMenu(this, findViewById(R.id.screenHeaderAction)).apply {
                menu.add(0, 1, 0, R.string.auto_pick_vehicle)
                menu.add(0, 2, 1, R.string.auto_add_vehicle)
                menu.add(0, 3, 2, R.string.auto_delete_vehicle)
                setOnMenuItemClickListener {
                    if (!loading) when (it.itemId) {
                        1 -> showVehiclePicker()
                        2 -> showAddVehicleDialog()
                        3 -> showDeleteVehicleDialog()
                    }
                    true
                }
                show()
            }
        }
        repository = AutoRepository(BudgetDatabase.getInstance(this))
        manager = BudgetManager.getInstance(this)
        vehicleNameText = findViewById(R.id.vehicleNameText)
        fuelStatsText = findViewById(R.id.fuelStatsText)
        recycler = findViewById(R.id.autoRecycler)
        emptyState = findViewById(R.id.autoEmptyState)
        findViewById<View>(R.id.autoLoadStatus).setOnClickListener { refresh() }
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter
        vehicleNameText.setOnClickListener { showVehiclePicker() }
        findViewById<View>(R.id.autoAddEventButton).setOnClickListener {
            if (!loading) AlertDialog.Builder(this).setTitle(R.string.design_auto_add_event)
                .setItems(EntryType.entries.map { getString(it.labelRes) }.toTypedArray()) { _, index ->
                    showEntryDialog(EntryType.entries[index])
                }.setNegativeButton(android.R.string.cancel, null).show()
        }
        findViewById<View>(R.id.autoStatisticsButton).setOnClickListener {
            if (!loading) startActivity(Intent(this, AutoStatisticsActivity::class.java).putExtra("vehicleId", vehicleId))
        }
        setupFilterButtons()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("auto_vehicle", vehicleId)
        outState.putString("auto_filter", filter?.key)
        super.onSaveInstanceState(outState)
    }

    private fun setupFilterButtons() {
        val buttons = mapOf<EntryType?, Int>(
            null to R.id.filterAllButton,
            EntryType.SERVICE to R.id.filterServiceButton,
            EntryType.INSURANCE to R.id.filterInsuranceButton,
            EntryType.FUEL to R.id.filterFuelButton,
            EntryType.REPAIR to R.id.filterRepairButton,
        )
        buttons.forEach { (type, id) ->
            findViewById<View>(id).setOnClickListener {
                filter = type
                updateFilterSelection(buttons)
                applyFilter()
            }
        }
        updateFilterSelection(buttons)
    }

    private fun updateFilterSelection(buttons: Map<EntryType?, Int>) {
        buttons.forEach { (type, id) ->
            findViewById<View>(id).isSelected = filter == type
        }
    }

    private fun applyFilter() {
        val visible = if (filter == null) records else records.filter { it.type == filter }
        adapter.submit(visible.sortedByDescending { it.dateEpochDay })
        emptyState.visibility = if (visible.isEmpty()) View.VISIBLE else View.GONE
        emptyState.setText(if (filter == null) R.string.auto_empty else R.string.design_auto_empty_filter)
    }

    private fun refresh() {
        refreshJob?.cancel()
        refreshJob = lifecycleScope.launch {
          loading = true
          findViewById<View>(R.id.autoAddEventButton).isEnabled = false
          val status = findViewById<TextView>(R.id.autoLoadStatus)
          status.setText(R.string.auto_stats_loading)
          status.visibility = View.VISIBLE
          records = emptyList()
          adapter.submit(emptyList())
          emptyState.visibility = View.GONE
          findViewById<TextView>(R.id.autoMonthPaid).setText(R.string.analysis_value_pending)
          findViewById<TextView>(R.id.autoMonthPaymentDetails).text = ""
          fuelStatsText.visibility = View.GONE
          try {
            val selectedId = vehicleId
            val loaded = withContext(Dispatchers.IO) {
                var available = repository.getAllVehicles()
                if (available.isEmpty()) {
                    repository.ensureDefaultVehicle(getString(R.string.auto_default_vehicle))
                    available = repository.getAllVehicles()
                }
                val id = available.firstOrNull { it.id == selectedId }?.id ?: available.first().id
                Triple(available, id, loadRecords(id)) to repository.fuelConsumptionPer100Km(id)
            }
            vehicles = loaded.first.first
            vehicleId = loaded.first.second
            records = loaded.first.third
            vehicleNameText.text = vehicles.firstOrNull { it.id == vehicleId }?.name ?: ""
            applyFilter()
            val consumption = loaded.second
            fuelStatsText.text = consumption?.let {
                getString(R.string.auto_fuel_stats, MoneyFormat.formatQuantity(it))
            } ?: ""
            fuelStatsText.visibility = if (consumption == null) View.GONE else View.VISIBLE
            val today = LocalDate.now()
            val summary = ru.mybudget.app.auto.AutoStatisticsHelper.summarize(records.map { it.toEntry() }, vehicleId,
                today.withDayOfMonth(1).toEpochDay(), today.withDayOfMonth(today.lengthOfMonth()).toEpochDay())
            findViewById<TextView>(R.id.autoMonthLabel).text = getString(R.string.design_auto_month_paid,
                MonthlyPlanHelper.formatMonthLabel(today.year, today.monthValue))
            findViewById<TextView>(R.id.autoMonthPaid).text = MoneyFormat.formatRub(summary.totalPaid)
            findViewById<TextView>(R.id.autoMonthPaymentDetails).text = getString(R.string.design_auto_month_methods,
                MoneyFormat.formatRub(summary.fromBudget), MoneyFormat.formatRub(summary.cash), MoneyFormat.formatRub(summary.unpaid))
            status.visibility = View.GONE
          } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled
          } catch (_: Exception) {
            status.setText(R.string.auto_stats_load_failed)
          } finally {
            loading = false
            findViewById<View>(R.id.autoAddEventButton).isEnabled = vehicleId > 0
          }
        }
    }

    private suspend fun loadRecords(id: Int): List<Record> {
        val service = repository.getServiceLogs(id).map { Record.Service(it) }
        val insurance = repository.getInsurances(id).map { Record.Insurance(it) }
        val fuel = repository.getFuelLogs(id).map { Record.Fuel(it) }
        val repair = repository.getRepairs(id).map { Record.Repair(it) }
        return service + insurance + fuel + repair
    }

    private fun showAddVehicleDialog() {
        val input = EditText(this).apply {
            hint = getString(R.string.auto_vehicle_name_hint)
            setSingleLine()
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.auto_add_vehicle)
            .setView(input)
            .setPositiveButton(R.string.budget_add_category_btn) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) return@setPositiveButton
                lifecycleScope.launch {
                    val newId = withContext(Dispatchers.IO) { repository.insertVehicle(name) }
                    vehicleId = newId
                    refresh()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showVehiclePicker() {
        if (loading || vehicles.isEmpty()) return
        val choices = vehicles.toList()
        val names = choices.map { it.name }.toTypedArray()
        val checked = choices.indexOfFirst { it.id == vehicleId }
        AlertDialog.Builder(this)
            .setTitle(R.string.auto_pick_vehicle)
            .setSingleChoiceItems(names, checked) { dialog, which ->
                vehicleId = choices[which].id
                dialog.dismiss()
                refresh()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.auto_add_vehicle) { _, _ -> showAddVehicleDialog() }
            .show()
    }

    private fun showDeleteVehicleDialog() {
        val current = vehicles.firstOrNull { it.id == vehicleId } ?: return
        if (vehicles.size <= 1) {
            Toast.makeText(this, R.string.auto_last_vehicle, Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.auto_delete_vehicle_title, current.name))
            .setMessage(R.string.auto_delete_vehicle_message)
            .setPositiveButton(R.string.delete) { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    repository.deleteVehicle(current.id)
                    refresh()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showEntryDialog(type: EntryType, existing: Record? = null) {
        lifecycleScope.launch {
            val cats = manager.getCategoriesAsync().filter { it.isActive && !manager.hasSubcategories(it.id) }
            withContext(Dispatchers.Main) { showEntryDialogOnMain(type, cats, existing) }
        }
    }

    private fun showEntryDialogOnMain(type: EntryType, cats: List<BudgetCategory>, existing: Record?) {
        val layout = layoutInflater.inflate(R.layout.dialog_auto_entry, null)
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

        val itemRows = mutableListOf<Pair<EditText, EditText>>()
        fun recalcTotal() {
            if (itemRows.isEmpty()) return
            val sum = itemRows.sumOf { (_, price) -> MoneyFormat.parseQuantity(price.text) ?: 0.0 }
            amountInput.setText(MoneyFormat.roundMoney(sum).toString())
        }
        fun addRow() {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, resources.getDimensionPixelSize(R.dimen.space_8), 0, 0)
            }
            val nameEdit = EditText(this, null, 0, R.style.Widget_MyBudget_OperationInput).apply {
                hint = getString(R.string.auto_item_name)
                inputType = android.text.InputType.TYPE_CLASS_TEXT
                layoutParams = LinearLayout.LayoutParams(-1, LinearLayout.LayoutParams.WRAP_CONTENT)
            }
            val priceEdit = EditText(this, null, 0, R.style.Widget_MyBudget_OperationInput).apply {
                hint = getString(R.string.auto_item_price)
                inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
                layoutParams = LinearLayout.LayoutParams(-1, LinearLayout.LayoutParams.WRAP_CONTENT)
                addTextChangedListener(object : android.text.TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun afterTextChanged(s: android.text.Editable?) { recalcTotal() }
                })
            }
            row.addView(nameEdit)
            row.addView(priceEdit)
            itemsContainer.addView(row)
            itemRows.add(nameEdit to priceEdit)
        }
        val multiItem = type == EntryType.SERVICE || type == EntryType.REPAIR
        if (multiItem) {
            itemsContainer.visibility = View.VISIBLE
            addItemButton.visibility = View.VISIBLE
            if (existing == null) addRow()
            addItemButton.setOnClickListener { addRow() }
        }

        val fuelAutoCalc = type == EntryType.FUEL
        if (fuelAutoCalc) {
            amountInput.hint = getString(R.string.auto_fuel_amount_auto)
            val watcher = object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) {
                    val liters = MoneyFormat.parseQuantity(litersInput.text) ?: 0.0
                    val price = MoneyFormat.parseQuantity(priceInput.text) ?: 0.0
                    amountInput.setText(MoneyFormat.roundMoney(liters * price).toString())
                }
            }
            litersInput.addTextChangedListener(watcher)
            priceInput.addTextChangedListener(watcher)
        }

        if (existing != null) {
            dateInput.setText(formatDay(existing.dateEpochDay))
            amountInput.setText(MoneyFormat.roundMoney(existing.amount).toString())
            val mileage = when (existing) {
                is Record.Service -> existing.log.mileageKm
                is Record.Fuel -> existing.log.mileageKm
                is Record.Repair -> existing.log.mileageKm
                else -> null
            }
            mileage?.let { mileageInput.setText(it.toString()) }
            if (multiItem) {
                val fullDesc = when (existing) {
                    is Record.Service -> existing.log.workDescription
                    is Record.Repair -> existing.log.orderDescription
                    else -> ""
                } ?: ""
                val (descLines, itemLines) = fullDesc.lines().partition { !isItemLine(it) }
                descInput.setText(descLines.joinToString("\n").trim())
                itemLines.forEach { line ->
                    addRow()
                    val (n, p) = splitItemLine(line)
                    itemRows.last().first.setText(n)
                    itemRows.last().second.setText(MoneyFormat.formatQuantity(p))
                }
            } else {
                when (existing) {
                    is Record.Service -> descInput.setText(existing.log.workDescription)
                    is Record.Repair -> descInput.setText(existing.log.orderDescription)
                    else -> {}
                }
            }
            if (existing is Record.Insurance) {
                endDateInput.setText(formatDay(existing.item.endDateEpochDay))
            }
            if (existing is Record.Fuel) {
                litersInput.setText(MoneyFormat.formatQuantity(existing.log.liters))
                priceInput.setText(MoneyFormat.formatQuantity(existing.log.pricePerLiter))
            }
        }

        if (existing == null) dateInput.setText(LocalDate.now().format(dateFormat))
        when (type) {
            EntryType.SERVICE -> {
                mileageInput.visibility = View.VISIBLE
                descInput.hint = getString(R.string.auto_field_work)
            }
            EntryType.INSURANCE -> {
                endDateInput.visibility = View.VISIBLE
                if (existing == null) {
                    endDateInput.setText(LocalDate.now().plusYears(1).format(dateFormat))
                }
            }
            EntryType.FUEL -> {
                litersInput.visibility = View.VISIBLE
                priceInput.visibility = View.VISIBLE
            }
            EntryType.REPAIR -> {
                mileageInput.visibility = View.VISIBLE
                descInput.hint = getString(R.string.auto_field_order)
            }
        }
        categorySpinner.adapter = ArrayAdapter(
            this,
            R.layout.item_operation_spinner,
            listOf(getString(R.string.auto_choose_category)) + cats.map { category ->
                val parent = manager.getCategories().firstOrNull { it.id == category.parentId }?.name
                if (parent.isNullOrBlank()) category.name else "$parent → ${category.name}"
            },
        )
        val saved = existing?.toEntry()?.categoryId?.toInt()
            ?: AutoPreferences.getDefaultCategoryId(this, type.key)
        val savedIndex = cats.indexOfFirst { it.id == saved }
        categorySpinner.setSelection(if (savedIndex >= 0) savedIndex + 1 else 0)

        paymentSpinner.adapter = ArrayAdapter(this, R.layout.item_operation_spinner,
            listOf(getString(R.string.auto_unpaid), getString(R.string.auto_payment_budget),
                getString(R.string.auto_payment_cash)))
        paymentSpinner.setSelection(AutoPaymentMethod.options.indexOf(
            existing?.toEntry()?.paymentMethod ?: AutoPaymentMethod.UNPAID).coerceAtLeast(0))
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
        val dialog = AlertDialog.Builder(this)
            .setTitle(if (existing != null) R.string.auto_edit else titleFor(type))
            .setView(layout)
            .setPositiveButton(R.string.save, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            val saveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            saveButton.setOnClickListener {
                val items = mutableListOf<Pair<String, Double>>()
                for ((name, price) in itemRows) {
                    val label = name.text.toString().trim()
                    val text = price.text.toString().trim()
                    if (label.isEmpty() && text.isEmpty()) continue
                    val sum = MoneyFormat.parse(text)
                    if (label.isEmpty() || sum == null || !sum.isFinite() || sum < 0.0) {
                        toast(R.string.auto_invalid_item)
                        return@setOnClickListener
                    }
                    items.add(label to sum)
                }
                submitEntry(type, existing, cats, categorySpinner, paymentSpinner,
                    dateInput, endDateInput, mileageInput, litersInput, priceInput, descInput,
                    amountInput, items, onBusy = { busy ->
                        saveButton.isEnabled = !busy
                        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled = !busy
                        dialog.setCancelable(!busy)
                    }, onSaved = { dialog.dismiss() })
            }
        }
        dialog.show()
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

    private fun Record.toEntry(): AutoEntry = when (this) {
        is Record.Service -> AutoEntry.from(log)
        is Record.Insurance -> AutoEntry.from(item)
        is Record.Fuel -> AutoEntry.from(log)
        is Record.Repair -> AutoEntry.from(log)
    }

    private fun submitEntry(
        type: EntryType, existing: Record?, cats: List<BudgetCategory>,
        categorySpinner: Spinner, paymentSpinner: Spinner, dateInput: EditText,
        endDateInput: EditText, mileageInput: EditText, litersInput: EditText,
        priceInput: EditText, descInput: EditText, amountInput: EditText,
        items: List<Pair<String, Double>>, onBusy: (Boolean) -> Unit, onSaved: () -> Unit,
    ) {
        val date = parseDate(dateInput.text.toString()) ?: return toast(R.string.auto_bad_date)
        val method = AutoPaymentMethod.options.getOrNull(paymentSpinner.selectedItemPosition) ?: return
        val category = cats.getOrNull(categorySpinner.selectedItemPosition - 1)
        if (method == AutoPaymentMethod.BUDGET && category == null) return toast(R.string.auto_no_categories)
        val mileageText = mileageInput.text.toString().trim()
        val mileage = mileageText.toIntOrNull()
        if (type != EntryType.INSURANCE && mileageText.isNotEmpty() && (mileage == null || mileage < 0)) {
            return toast(R.string.auto_invalid_mileage)
        }
        val end = if (type == EntryType.INSURANCE) {
            parseDate(endDateInput.text.toString()) ?: return toast(R.string.auto_bad_date)
        } else null
        if (end != null && end < date) return toast(R.string.auto_invalid_insurance_dates)
        val liters = if (type == EntryType.FUEL) MoneyFormat.parseQuantity(litersInput.text) else 0.0
        val price = if (type == EntryType.FUEL) MoneyFormat.parseQuantity(priceInput.text) else 0.0
        if (liters == null || price == null || !liters.isFinite() || !price.isFinite() ||
            (type == EntryType.FUEL && (liters <= 0.0 || price < 0.0))) {
            return toast(R.string.auto_bad_amount)
        }
        var description = descInput.text.toString().trim()
        val amount = when {
            type == EntryType.FUEL -> MoneyFormat.roundMoney(liters * price)
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
        val old = existing?.toEntry()
        val entry = AutoEntry(type = AutoEntryType.valueOf(type.name), id = old?.id ?: 0,
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
                            BudgetDatabase.getInstance(this@AutoActivity).budgetDao().getTransactionById(it.toInt())
                        }?.takeIf { it.categoryId == category!!.id }?.let {
                            if (it.type == "expense") it.amount else -it.amount
                        } ?: 0.0
                        repository.getCategoryBalance(category!!.id) + credit
                    }
                    if (available < amount && !confirmOverspend(amount - available)) return@launch
                }
                withContext(Dispatchers.IO) { repository.saveEntry(entry) }
                onSaved()
                if (method == AutoPaymentMethod.BUDGET) {
                    AutoPreferences.setDefaultCategoryId(this@AutoActivity, type.key, category!!.id)
                }
                manager.reloadCategoriesFromDatabase()
                BudgetWidgetProvider.updateAll(this@AutoActivity)
                if (method == AutoPaymentMethod.BUDGET) {
                    OverspendNotifier.checkAfterExpense(applicationContext, category!!.id)
                }
                refresh()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Toast.makeText(this@AutoActivity, getString(R.string.auto_save_failed, e.message.orEmpty()),
                    Toast.LENGTH_LONG).show()
            } finally {
                onBusy(false)
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

    private fun showRecordDialog(record: Record) {
        val view = layoutInflater.inflate(R.layout.dialog_auto_record, null)
        val details = view.findViewById<TextView>(R.id.recordDetails)
        details.text = buildString {
            append(formatDay(record.dateEpochDay))
            append("\n").append(MoneyFormat.formatRub(record.amount))
            append("\n").append(paymentLabel(record.toEntry().paymentMethod))
        }
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(record.title.ifBlank { getString(R.string.main_menu_auto) })
            .setView(view)
            .create()
        view.findViewById<Button>(R.id.payButton).visibility =
            if (record.paid) View.GONE else View.VISIBLE
        view.findViewById<Button>(R.id.payButton).setOnClickListener {
            dialog.dismiss()
            confirmPay(record)
        }
        view.findViewById<Button>(R.id.editButton).setOnClickListener {
            dialog.dismiss()
            showEntryDialog(record.type, record)
        }
        view.findViewById<Button>(R.id.deleteButton).setOnClickListener {
            dialog.dismiss()
            confirmDelete(record)
        }
        view.findViewById<Button>(R.id.cancelButton).setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun confirmPay(record: Record) {
        showEntryDialog(record.type, record)
    }

    private fun paymentLabel(method: String): String = getString(when (method) {
        AutoPaymentMethod.BUDGET -> R.string.auto_payment_budget
        AutoPaymentMethod.CASH -> R.string.auto_payment_cash
        else -> R.string.auto_unpaid
    })

    private fun confirmDelete(record: Record) {
        AlertDialog.Builder(this)
            .setTitle(R.string.delete)
            .setMessage(R.string.auto_delete_record_message)
            .setPositiveButton(R.string.delete) { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    when (record) {
                        is Record.Service -> repository.deleteServiceLog(record.log.id)
                        is Record.Insurance -> repository.deleteInsurance(record.item.id)
                        is Record.Fuel -> repository.deleteFuelLog(record.log.id)
                        is Record.Repair -> repository.deleteRepair(record.log.id)
                    }
                    refresh()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun titleFor(type: EntryType): Int = when (type) {
        EntryType.SERVICE -> R.string.auto_add_service
        EntryType.INSURANCE -> R.string.auto_add_insurance
        EntryType.FUEL -> R.string.auto_add_fuel
        EntryType.REPAIR -> R.string.auto_add_repair
    }

    private fun parseDate(text: String): Long? = runCatching {
        LocalDate.parse(text.trim(), dateFormat).toEpochDay()
    }.getOrNull()

    private fun toast(res: Int) {
        Toast.makeText(this, res, Toast.LENGTH_SHORT).show()
    }

    private class RecordAdapter(
        val onClick: (Record) -> Unit,
    ) : RecyclerView.Adapter<RecordAdapter.Holder>() {

        private var items: List<Record> = emptyList()

        fun submit(list: List<Record>) {
            items = list
            notifyDataSetChanged()
        }

        class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val number: TextView = view.findViewById(R.id.recordNumber)
            val type: TextView = view.findViewById(R.id.recordType)
            val title: TextView = view.findViewById(R.id.recordTitle)
            val subtitle: TextView = view.findViewById(R.id.recordSubtitle)
            val amount: TextView = view.findViewById(R.id.recordAmount)
            val date: TextView = view.findViewById(R.id.recordDate)
            val status: TextView = view.findViewById(R.id.recordStatus)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_auto_record, parent, false)
            return Holder(view)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val record = items[position]
            val ctx = holder.itemView.context
            holder.number.text = (position + 1).toString()
            holder.type.text = ctx.getString(record.type.labelRes)
            holder.title.text = record.title.ifBlank { ctx.getString(record.type.labelRes) }
            holder.subtitle.text = record.subtitle
            holder.subtitle.visibility = if (record.subtitle.isBlank()) View.GONE else View.VISIBLE
            holder.amount.text = MoneyFormat.formatRub(record.amount)
            holder.date.text = formatDay(record.dateEpochDay)
            holder.status.text = ctx.getString(when (record.paymentMethod) {
                AutoPaymentMethod.CASH -> R.string.auto_payment_cash_short
                AutoPaymentMethod.BUDGET -> R.string.auto_payment_budget_short
                else -> R.string.auto_unpaid
            })
            holder.status.setTextColor(ContextCompat.getColor(ctx,
                if (record.paid) R.color.primary_green else R.color.expense_red))
            holder.itemView.setOnClickListener { onClick(record) }
        }

        override fun getItemCount(): Int = items.size
    }

    companion object {
        fun formatDay(epochDay: Long): String =
            LocalDate.ofEpochDay(epochDay).format(DateTimeFormatter.ofPattern("dd.MM.yyyy"))

        fun todayEpochDay(): Long = ChronoUnit.DAYS.between(
            LocalDate.of(1970, 1, 1),
            LocalDate.now(),
        )
    }
}
