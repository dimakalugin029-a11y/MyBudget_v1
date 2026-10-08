package ru.mybudget.app

import android.os.Bundle
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
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
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

    private enum class EntryType(val key: String) {
        SERVICE("service"),
        INSURANCE("insurance"),
        FUEL("fuel"),
        REPAIR("repair"),
    }

    private sealed class Record {
        abstract val dateEpochDay: Long
        abstract val amount: Double
        abstract val title: String
        abstract val subtitle: String
        abstract val paid: Boolean

        data class Service(val log: ru.mybudget.app.data.VehicleServiceLogEntity) : Record() {
            override val dateEpochDay = log.dateEpochDay
            override val amount = log.amount
            override val title = log.workDescription.ifBlank { "" }
            override val subtitle = buildString {
                append("ТО")
                log.mileageKm?.let { append(" · ").append(it).append(" км") }
            }
            override val paid = log.paidTransactionId != null
        }

        data class Insurance(val item: ru.mybudget.app.data.VehicleInsuranceEntity) : Record() {
            override val dateEpochDay = item.startDateEpochDay
            override val amount = item.amount
            override val title = "ОСАГО"
            override val subtitle = buildString {
                append(formatDay(item.startDateEpochDay))
                append(" — ")
                append(formatDay(item.endDateEpochDay))
                val days = AutoRepository.daysUntil(item.endDateEpochDay, todayEpochDay())
                if (days >= 0) append(" · ").append(days).append(" дн.")
                else append(" · истекла")
            }
            override val paid = item.paidTransactionId != null
        }

        data class Fuel(val log: ru.mybudget.app.data.VehicleFuelLogEntity) : Record() {
            override val dateEpochDay = log.dateEpochDay
            override val amount = log.amount
            override val title = "Бензин"
            override val subtitle = buildString {
                append(formatDay(log.dateEpochDay))
                append(" · ").append(MoneyFormat.formatQuantity(log.liters)).append(" л")
                append(" · ").append(MoneyFormat.formatQuantity(log.pricePerLiter)).append(" ₽/л")
                log.mileageKm?.let { append(" · ").append(it).append(" км") }
            }
            override val paid = log.paidTransactionId != null
        }

        data class Repair(val log: ru.mybudget.app.data.VehicleRepairEntity) : Record() {
            override val dateEpochDay = log.dateEpochDay
            override val amount = log.amount
            override val title = log.orderDescription.ifBlank { "Ремонт" }
            override val subtitle = buildString {
                append("Ремонт")
                log.mileageKm?.let { append(" · ").append(it).append(" км") }
            }
            override val paid = log.paidTransactionId != null
        }
    }

    private lateinit var repository: AutoRepository
    private lateinit var manager: BudgetManager
    private var vehicleId: Int = 0
    private var vehicles: List<ru.mybudget.app.data.VehicleEntity> = emptyList()
    private var records: List<Record> = emptyList()

    private lateinit var vehicleNameText: TextView
    private lateinit var fuelStatsText: TextView
    private lateinit var recycler: RecyclerView
    private lateinit var emptyState: TextView

    private val adapter = RecordAdapter(
        onClick = { showRecordDialog(it) },
    )

    private val dateFormat = DateTimeFormatter.ISO_LOCAL_DATE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_auto)
        ScreenHeaderHelper.setup(this, getString(R.string.main_menu_auto), getString(R.string.main_icon_auto))
        repository = AutoRepository(
            BudgetDatabase.getInstance(this).autoDao(),
            BudgetDatabase.getInstance(this).budgetDao(),
        )
        manager = BudgetManager.getInstance(this)
        vehicleNameText = findViewById(R.id.vehicleNameText)
        fuelStatsText = findViewById(R.id.fuelStatsText)
        recycler = findViewById(R.id.autoRecycler)
        emptyState = findViewById(R.id.autoEmptyState)
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter
        findViewById<View>(R.id.addVehicleButton).setOnClickListener { showAddVehicleDialog() }
        findViewById<View>(R.id.deleteVehicleButton).setOnClickListener { showDeleteVehicleDialog() }
        vehicleNameText.setOnClickListener { showVehiclePicker() }
        findViewById<View>(R.id.addServiceButton).setOnClickListener { showEntryDialog(EntryType.SERVICE) }
        findViewById<View>(R.id.addInsuranceButton).setOnClickListener { showEntryDialog(EntryType.INSURANCE) }
        findViewById<View>(R.id.addFuelButton).setOnClickListener { showEntryDialog(EntryType.FUEL) }
        findViewById<View>(R.id.addRepairButton).setOnClickListener { showEntryDialog(EntryType.REPAIR) }
        refresh()
    }

    private fun refresh() {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                vehicles = repository.getAllVehicles()
                if (vehicles.isEmpty()) {
                    repository.ensureDefaultVehicle(getString(R.string.auto_default_vehicle))
                    vehicles = repository.getAllVehicles()
                }
                vehicleId = vehicles.firstOrNull { it.id == vehicleId }?.id ?: vehicles.first().id
                loadRecords()
            }
            vehicleNameText.text = vehicles.firstOrNull { it.id == vehicleId }?.name ?: ""
            adapter.submit(records.sortedByDescending { it.dateEpochDay })
            emptyState.visibility = if (records.isEmpty()) View.VISIBLE else View.GONE
            val consumption = withContext(Dispatchers.IO) { repository.fuelConsumptionPer100Km(vehicleId) }
            fuelStatsText.text = consumption?.let {
                getString(R.string.auto_fuel_stats, MoneyFormat.formatQuantity(it))
            } ?: ""
        }
    }

    private suspend fun loadRecords() {
        val service = repository.getServiceLogs(vehicleId).map { Record.Service(it) }
        val insurance = repository.getInsurances(vehicleId).map { Record.Insurance(it) }
        val fuel = repository.getFuelLogs(vehicleId).map { Record.Fuel(it) }
        val repair = repository.getRepairs(vehicleId).map { Record.Repair(it) }
        records = service + insurance + fuel + repair
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
        if (vehicles.size <= 1) return
        val names = vehicles.map { it.name }.toTypedArray()
        val checked = vehicles.indexOfFirst { it.id == vehicleId }
        AlertDialog.Builder(this)
            .setTitle(R.string.auto_pick_vehicle)
            .setSingleChoiceItems(names, checked) { dialog, which ->
                vehicleId = vehicles[which].id
                dialog.dismiss()
                refresh()
            }
            .setNegativeButton(android.R.string.cancel, null)
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

    private fun showEntryDialog(type: EntryType) {
        lifecycleScope.launch {
            val cats = manager.getCategoriesAsync().filter { !manager.hasSubcategories(it.id) }
            if (cats.isEmpty()) {
                Toast.makeText(this@AutoActivity, R.string.auto_no_categories, Toast.LENGTH_SHORT).show()
                return@launch
            }
            withContext(Dispatchers.Main) { showEntryDialogOnMain(type, cats) }
        }
    }

    private fun showEntryDialogOnMain(type: EntryType, cats: List<BudgetCategory>) {
        val layout = layoutInflater.inflate(R.layout.dialog_auto_entry, null)
        val dateInput = layout.findViewById<EditText>(R.id.dateInput)
        val endDateInput = layout.findViewById<EditText>(R.id.endDateInput)
        val mileageInput = layout.findViewById<EditText>(R.id.mileageInput)
        val litersInput = layout.findViewById<EditText>(R.id.litersInput)
        val priceInput = layout.findViewById<EditText>(R.id.priceInput)
        val descInput = layout.findViewById<EditText>(R.id.descInput)
        val amountInput = layout.findViewById<EditText>(R.id.amountInput)
        val categorySpinner = layout.findViewById<Spinner>(R.id.categorySpinner)
        val payNowSwitch = layout.findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.payNowSwitch)
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
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 4, 0, 0)
            }
            val nameEdit = EditText(this).apply {
                hint = getString(R.string.auto_item_name)
                inputType = android.text.InputType.TYPE_CLASS_TEXT
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val priceEdit = EditText(this).apply {
                hint = getString(R.string.auto_item_price)
                inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
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
            addRow()
            addItemButton.setOnClickListener { addRow() }
        }

        dateInput.setText(LocalDate.now().format(dateFormat))
        when (type) {
            EntryType.SERVICE -> {
                mileageInput.visibility = View.VISIBLE
                descInput.hint = getString(R.string.auto_field_work)
            }
            EntryType.INSURANCE -> {
                endDateInput.visibility = View.VISIBLE
                endDateInput.setText(LocalDate.now().plusYears(1).format(dateFormat))
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
            android.R.layout.simple_spinner_dropdown_item,
            cats.map { it.name },
        )
        val saved = AutoPreferences.getDefaultCategoryId(this, type.key)
        val savedIndex = cats.indexOfFirst { it.id == saved }
        if (savedIndex >= 0) categorySpinner.setSelection(savedIndex)

        AlertDialog.Builder(this)
            .setTitle(titleFor(type))
            .setView(layout)
            .setPositiveButton(R.string.budget_add_category_btn) { _, _ ->
                val items = itemRows.mapNotNull { (name, price) ->
                    val n = name.text.toString().trim()
                    val p = MoneyFormat.parseQuantity(price.text) ?: return@mapNotNull null
                    if (n.isEmpty() && p <= 0.0) null else n to p
                }
                saveEntry(type, cats, categorySpinner, dateInput, endDateInput, mileageInput, litersInput, priceInput, descInput, amountInput, payNowSwitch, items)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun saveEntry(
        type: EntryType,
        cats: List<BudgetCategory>,
        categorySpinner: Spinner,
        dateInput: EditText,
        endDateInput: EditText,
        mileageInput: EditText,
        litersInput: EditText,
        priceInput: EditText,
        descInput: EditText,
        amountInput: EditText,
        payNowSwitch: androidx.appcompat.widget.SwitchCompat,
        items: List<Pair<String, Double>>,
    ) {
        val date = parseDate(dateInput.text.toString()) ?: return toast(R.string.auto_bad_date)
        val category = cats.getOrNull(categorySpinner.selectedItemPosition) ?: return
        val mileage = mileageInput.text.toString().toIntOrNull()
        var description = descInput.text.toString().trim()
        val payNow = payNowSwitch.isChecked
        val (amount, extra) = when (type) {
            EntryType.SERVICE, EntryType.REPAIR -> {
                if (items.isNotEmpty()) {
                    val sum = MoneyFormat.roundMoney(items.sumOf { it.second })
                    if (sum <= 0.0) return toast(R.string.auto_bad_amount)
                    val lines = items.joinToString("\n") { (n, p) -> "$n — ${MoneyFormat.formatRub(p)}" }
                    if (description.isNotEmpty()) description = "$description\n$lines" else description = lines
                    sum to null
                } else {
                    val a = MoneyFormat.parse(amountInput.text) ?: return toast(R.string.auto_bad_amount)
                    a to null
                }
            }
            EntryType.INSURANCE -> {
                val end = parseDate(endDateInput.text.toString()) ?: return toast(R.string.auto_bad_date)
                val a = MoneyFormat.parse(amountInput.text) ?: return toast(R.string.auto_bad_amount)
                a to end
            }
            EntryType.FUEL -> {
                val liters = MoneyFormat.parseQuantity(litersInput.text) ?: return toast(R.string.auto_bad_amount)
                val price = MoneyFormat.parseQuantity(priceInput.text) ?: return toast(R.string.auto_bad_amount)
                MoneyFormat.roundMoney(liters * price) to (liters to price)
            }
        }
        if (amount <= 0.0) return toast(R.string.auto_bad_amount)
        AutoPreferences.setDefaultCategoryId(this, type.key, category.id)

        val proceed: () -> Unit = {
            lifecycleScope.launch(Dispatchers.IO) {
                when (type) {
                    EntryType.SERVICE -> repository.addServiceLog(
                        ru.mybudget.app.data.VehicleServiceLogEntity(
                            vehicleId = vehicleId, dateEpochDay = date, mileageKm = mileage,
                            workDescription = description, amount = amount, categoryId = category.id.toLong(),
                        ),
                        payNow, description,
                    )
                    EntryType.INSURANCE -> repository.addInsurance(
                        ru.mybudget.app.data.VehicleInsuranceEntity(
                            vehicleId = vehicleId, startDateEpochDay = date, endDateEpochDay = extra as Long,
                            amount = amount, categoryId = category.id.toLong(),
                        ),
                        payNow, description,
                    )
                    EntryType.FUEL -> {
                        val (liters, price) = extra as Pair<Double, Double>
                        repository.addFuelLog(
                            ru.mybudget.app.data.VehicleFuelLogEntity(
                                vehicleId = vehicleId, dateEpochDay = date, liters = liters,
                                pricePerLiter = price, amount = amount, mileageKm = mileage,
                                categoryId = category.id.toLong(),
                            ),
                            payNow, description,
                        )
                    }
                    EntryType.REPAIR -> repository.addRepair(
                        ru.mybudget.app.data.VehicleRepairEntity(
                            vehicleId = vehicleId, dateEpochDay = date, mileageKm = mileage,
                            orderDescription = description, amount = amount, categoryId = category.id.toLong(),
                        ),
                        payNow, description,
                    )
                }
                refresh()
            }
        }

        if (payNow) {
            lifecycleScope.launch {
                val balance = withContext(Dispatchers.IO) { repository.getCategoryBalance(category.id) }
                if (balance < amount) {
                    AlertDialog.Builder(this@AutoActivity)
                        .setTitle(R.string.auto_negative_title)
                        .setMessage(getString(R.string.auto_negative_message, MoneyFormat.formatRub(amount - balance)))
                        .setPositiveButton(R.string.budget_add_category_btn) { _, _ -> proceed() }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                } else {
                    proceed()
                }
            }
        } else {
            proceed()
        }
    }

    private fun showRecordDialog(record: Record) {
        val message = buildString {
            append(record.subtitle)
            append("\n").append(MoneyFormat.formatRub(record.amount))
            append("\n").append(if (record.paid) getString(R.string.auto_paid) else getString(R.string.auto_unpaid))
        }
        val builder = AlertDialog.Builder(this)
            .setTitle(record.title.ifBlank { getString(R.string.main_menu_auto) })
            .setMessage(message)
            .setNegativeButton(android.R.string.cancel, null)
        if (record.paid) {
            builder.setPositiveButton(R.string.delete) { _, _ -> confirmDelete(record) }
        } else {
            builder.setPositiveButton(R.string.auto_pay) { _, _ -> confirmPay(record) }
            builder.setNeutralButton(R.string.delete) { _, _ -> confirmDelete(record) }
        }
        builder.show()
    }

    private fun confirmPay(record: Record) {
        val categoryId = when (record) {
            is Record.Service -> record.log.categoryId
            is Record.Insurance -> record.item.categoryId
            is Record.Fuel -> record.log.categoryId
            is Record.Repair -> record.log.categoryId
        }.toInt()
        lifecycleScope.launch {
            val balance = withContext(Dispatchers.IO) { repository.getCategoryBalance(categoryId) }
            if (balance < record.amount) {
                AlertDialog.Builder(this@AutoActivity)
                    .setTitle(R.string.auto_negative_title)
                    .setMessage(getString(R.string.auto_negative_message, MoneyFormat.formatRub(record.amount - balance)))
                    .setPositiveButton(R.string.auto_pay) { _, _ -> payRecord(record, categoryId) }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            } else {
                payRecord(record, categoryId)
            }
        }
    }

    private fun payRecord(record: Record, categoryId: Int) {
        lifecycleScope.launch(Dispatchers.IO) {
            val txId = repository.payExpense(categoryId, record.amount, record.title, record.dateEpochDay)
            when (record) {
                is Record.Service -> repository.markServiceLogPaid(record.log.id, txId)
                is Record.Insurance -> repository.markInsurancePaid(record.item.id, txId)
                is Record.Fuel -> repository.markFuelLogPaid(record.log.id, txId)
                is Record.Repair -> repository.markRepairPaid(record.log.id, txId)
            }
            refresh()
        }
    }

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
            val title: TextView = view.findViewById(R.id.recordTitle)
            val subtitle: TextView = view.findViewById(R.id.recordSubtitle)
            val amount: TextView = view.findViewById(R.id.recordAmount)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_auto_record, parent, false)
            return Holder(view)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val record = items[position]
            holder.title.text = record.title.ifBlank {
                holder.itemView.context.getString(R.string.main_menu_auto)
            }
            holder.subtitle.text = record.subtitle + " · " +
                if (record.paid) holder.itemView.context.getString(R.string.auto_paid)
                else holder.itemView.context.getString(R.string.auto_unpaid)
            holder.amount.text = MoneyFormat.formatRub(record.amount)
            holder.itemView.setOnClickListener { onClick(record) }
        }

        override fun getItemCount(): Int = items.size
    }

    companion object {
        fun formatDay(epochDay: Long): String =
            LocalDate.ofEpochDay(epochDay).format(DateTimeFormatter.ISO_LOCAL_DATE)

        fun todayEpochDay(): Long = ChronoUnit.DAYS.between(
            LocalDate.of(1970, 1, 1),
            LocalDate.now(),
        )
    }
}
