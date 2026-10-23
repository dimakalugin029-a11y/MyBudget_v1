package ru.mybudget.app

import android.os.Bundle
import android.content.Intent
import ru.mybudget.app.auto.AutoEntry
import ru.mybudget.app.auto.AutoPaymentMethod
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import android.widget.PopupMenu
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.activity.viewModels
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
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
        val isArchived:Boolean get() = when(this) {
            is Service -> log.isArchived
            is Insurance -> item.isArchived
            is Fuel -> log.isArchived
            is Repair -> log.isArchived
        }
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
            override val title = ru.mybudget.app.auto.InsuranceType.label(item.insuranceType)
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
            override val title = log.fuelType.ifBlank { "Топливо" }
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
    private var showArchivedEvents = false
    private val journalModel:AutoJournalViewModel by viewModels()
    private val mutating get() = journalModel.state.value != AutoJournalViewModel.State.Idle
    private val archivedVehicle get() = vehicles.firstOrNull { it.id == vehicleId }?.isArchived == true

    private lateinit var vehicleNameText: TextView
    private lateinit var fuelStatsText: TextView
    private lateinit var recycler: RecyclerView
    private lateinit var emptyState: TextView

    private val adapter = RecordAdapter(
        onClick = { if (!loading && !mutating) showRecordDialog(it) },
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_auto)
        ScreenHeaderHelper.setup(this, getString(R.string.main_menu_auto))
        onBackPressedDispatcher.addCallback(this,object:OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { if(!mutating) finish() }
        })
        vehicleId = savedInstanceState?.getInt("auto_vehicle", 0) ?: 0
        showArchivedEvents = savedInstanceState?.getBoolean("auto_show_archived") ?: false
        filter = EntryType.entries.firstOrNull { it.key == savedInstanceState?.getString("auto_filter") }
        ScreenHeaderHelper.bindAction(this, R.drawable.ic_nav_more, R.string.design_auto_manage) {
            PopupMenu(this, findViewById(R.id.screenHeaderAction)).apply {
                menu.add(0, 1, 0, R.string.auto_pick_vehicle)
                menu.add(0, 2, 1, R.string.auto_add_vehicle)
                menu.add(0, 3, 2, if(archivedVehicle) R.string.auto_restore_vehicle else R.string.auto_archive_vehicle)
                menu.add(0,4,3,R.string.auto_show_archived_events).apply { isCheckable=true;isChecked=showArchivedEvents }
                setOnMenuItemClickListener {
                    if (!loading && !mutating) when (it.itemId) {
                        1 -> showVehiclePicker()
                        2 -> showAddVehicleDialog()
                        3 -> showArchiveVehicleDialog()
                        4 -> { showArchivedEvents=!showArchivedEvents;applyFilter() }
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
            if (!loading && !mutating && !archivedVehicle) AlertDialog.Builder(this).setTitle(R.string.design_auto_add_event)
                .setItems(EntryType.entries.map { getString(it.labelRes) }.toTypedArray()) { _, index ->
                    showEntryDialog(EntryType.entries[index])
                }.setNegativeButton(android.R.string.cancel, null).show()
        }
        findViewById<View>(R.id.autoStatisticsButton).setOnClickListener {
            if (!loading && !mutating) startActivity(Intent(this, AutoStatisticsActivity::class.java).putExtra("vehicleId", vehicleId))
        }
        setupFilterButtons()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                journalModel.state.collect { state ->
                    findViewById<View>(R.id.autoAddEventButton).isEnabled = !loading && !mutating && !archivedVehicle && vehicleId>0
                    when(state) {
                        AutoJournalViewModel.State.Done -> { journalModel.acknowledge();refresh() }
                        is AutoJournalViewModel.State.Failed -> {
                            Toast.makeText(this@AutoActivity,getString(R.string.auto_save_failed,state.message),Toast.LENGTH_LONG).show()
                            journalModel.acknowledge();refresh()
                        }
                        else -> Unit
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("auto_vehicle", vehicleId)
        outState.putBoolean("auto_show_archived", showArchivedEvents)
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
        val visible = records.filter { (filter == null || it.type == filter) && (showArchivedEvents || !it.isArchived) }
        adapter.submit(visible.sortedByDescending { it.dateEpochDay })
        emptyState.visibility = if (visible.isEmpty()) View.VISIBLE else View.GONE
        emptyState.setText(if(records.any { it.isArchived } && !showArchivedEvents) R.string.auto_only_archived_events
            else if (filter == null) R.string.auto_empty else R.string.design_auto_empty_filter)
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
                val id = available.firstOrNull { it.id == selectedId }?.id ?: available.firstOrNull { !it.isArchived }?.id ?: available.first().id
                Triple(available, id, loadRecords(id)) to repository.fuelConsumptionPer100Km(id)
            }
            vehicles = loaded.first.first
            vehicleId = loaded.first.second
            records = loaded.first.third
            vehicleNameText.text = vehicles.firstOrNull { it.id == vehicleId }?.let { vehicleLabel(it) } ?: ""
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
            findViewById<View>(R.id.autoAddEventButton).isEnabled = vehicleId > 0 && !archivedVehicle && !mutating
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
        if (loading || mutating || vehicles.isEmpty()) return
        val choices = vehicles.toList()
        val names = choices.map { vehicleLabel(it) }.toTypedArray()
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

    private fun vehicleLabel(vehicle:ru.mybudget.app.data.VehicleEntity):String =
        if(vehicle.isArchived) getString(R.string.auto_archived_name,vehicle.name) else vehicle.name

    private fun showArchiveVehicleDialog() {
        if(loading || mutating) return
        val current=vehicles.firstOrNull { it.id==vehicleId } ?: return
        AlertDialog.Builder(this)
            .setTitle(if(current.isArchived) R.string.auto_restore_vehicle else R.string.auto_archive_vehicle)
            .setMessage(getString(if(current.isArchived) R.string.auto_restore_vehicle_message else R.string.auto_archive_vehicle_message,current.name))
            .setPositiveButton(if(current.isArchived) R.string.auto_restore else R.string.auto_archive) { _,_ ->
                journalModel.execute(AutoJournalViewModel.Command.VehicleArchive(current.id,!current.isArchived))
            }.setNegativeButton(android.R.string.cancel,null).show()
    }

    private fun showEntryDialog(type: EntryType, existing: Record? = null) {
        if(loading || mutating || archivedVehicle || existing?.isArchived == true) return
        startActivity(Intent(this, AutoEntryActivity::class.java)
            .putExtra(AutoEntryActivity.EXTRA_TYPE, type.name)
            .putExtra(AutoEntryActivity.EXTRA_ID, existing?.toEntry()?.id ?: 0)
            .putExtra(AutoEntryActivity.EXTRA_VEHICLE, existing?.toEntry()?.vehicleId ?: vehicleId))
    }

    private fun Record.toEntry(): AutoEntry = when (this) {
        is Record.Service -> AutoEntry.from(log)
        is Record.Insurance -> AutoEntry.from(item)
        is Record.Fuel -> AutoEntry.from(log)
        is Record.Repair -> AutoEntry.from(log)
    }

    private fun showRecordDialog(record: Record) {
        val editable=!archivedVehicle && !record.isArchived
        val view = layoutInflater.inflate(R.layout.dialog_auto_record, null)
        val details = view.findViewById<TextView>(R.id.recordDetails)
        val entry=record.toEntry()
        view.findViewById<TextView>(R.id.autoDetailTotal).text=getString(R.string.auto_detail_total,MoneyFormat.formatRub(entry.amount))
        details.setTextIsSelectable(true)
        details.text = buildString {
            vehicles.firstOrNull { it.id==entry.vehicleId }?.let { append(it.name).append("\n") }
            append(getString(R.string.auto_detail_date,formatDay(entry.dateEpochDay)))
            entry.endDateEpochDay?.let { append("\n").append(getString(R.string.auto_detail_end_date,formatDay(it))) }
            entry.mileageKm?.let { append("\n").append(getString(R.string.auto_detail_mileage,it)) }
            append("\n").append(paymentLabel(entry.paymentMethod))
            if(entry.paymentMethod==AutoPaymentMethod.BUDGET) {
                val category=manager.getCategories().firstOrNull { it.id.toLong()==entry.categoryId }
                val parent=category?.let { c -> manager.getCategories().firstOrNull { it.id==c.parentId } }
                val name=category?.let { if(parent==null) it.name else "${parent.name} → ${it.name}" } ?: getString(R.string.auto_detail_missing_category)
                append("\n").append(getString(R.string.auto_detail_category,name))
            }
            if(entry.type==ru.mybudget.app.auto.AutoEntryType.INSURANCE) append("\n").append(ru.mybudget.app.auto.InsuranceType.label(entry.insuranceType))
            if(entry.fuelType.isNotBlank()) append("\n").append(getString(R.string.auto_meta_fuelType)).append(": ").append(entry.fuelType)
            if(entry.fuelStation.isNotBlank()) append("\n").append(getString(R.string.auto_meta_fuelStation)).append(": ").append(entry.fuelStation)
            if(entry.insuranceCompany.isNotBlank()) append("\n").append(getString(R.string.auto_meta_insuranceCompany)).append(": ").append(entry.insuranceCompany)
            if(entry.policyNumber.isNotBlank()) append("\n").append(getString(R.string.auto_meta_policyNumber)).append(": ").append(entry.policyNumber)
            if(entry.insurancePhone.isNotBlank()) append("\n").append(getString(R.string.auto_meta_insurancePhone)).append(": ").append(entry.insurancePhone)
            if(entry.type==ru.mybudget.app.auto.AutoEntryType.FUEL) {
                append("\n").append(getString(R.string.auto_detail_fuel,MoneyFormat.formatQuantity(entry.liters),MoneyFormat.formatQuantity(entry.pricePerLiter)))
            }
        }
        val multi=record is Record.Service || record is Record.Repair
        val parsed=if(multi) ru.mybudget.app.auto.AutoWorkDetails.parse(entry.description)
            else ru.mybudget.app.auto.AutoWorkDetails.Details(entry.description,emptyList())
        val works=view.findViewById<android.widget.LinearLayout>(R.id.autoDetailWorks)
        view.findViewById<View>(R.id.autoDetailWorksTitle).visibility=if(multi) View.VISIBLE else View.GONE
        parsed.items.forEachIndexed { index,item ->
            val row=layoutInflater.inflate(R.layout.item_auto_detail_work,works,false)
            row.findViewById<TextView>(R.id.autoDetailWorkName).text="${index+1}. ${item.first}"
            row.findViewById<TextView>(R.id.autoDetailWorkPrice).text=MoneyFormat.formatRub(item.second)
            works.addView(row)
        }
        if(multi && parsed.items.isEmpty()) works.addView(TextView(this).apply {
            setText(R.string.auto_detail_no_works);setTextAppearance(R.style.Text_MyBudget_Subtitle)
        })
        view.findViewById<View>(R.id.autoDetailCommentTitle).visibility=if(parsed.comment.isBlank()) View.GONE else View.VISIBLE
        view.findViewById<TextView>(R.id.autoDetailComment).apply {
            text=parsed.comment;visibility=if(parsed.comment.isBlank()) View.GONE else View.VISIBLE
        }
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(if(record is Record.Insurance) record.title else getString(record.type.labelRes))
            .setView(view)
            .create()
        view.findViewById<Button>(R.id.payButton).visibility =
            if (record.paid || !editable) View.GONE else View.VISIBLE
        view.findViewById<Button>(R.id.payButton).setOnClickListener {
            dialog.dismiss()
            confirmPay(record)
        }
        view.findViewById<Button>(R.id.editButton).visibility=if(editable) View.VISIBLE else View.GONE
        view.findViewById<Button>(R.id.deleteButton).apply {
            visibility=if(archivedVehicle) View.GONE else View.VISIBLE
            setText(if(record.isArchived) R.string.auto_restore else R.string.auto_event_actions)
        }
        if(archivedVehicle || record.isArchived) details.append("\n"+getString(R.string.auto_archive_read_only))
        view.findViewById<Button>(R.id.editButton).setOnClickListener {
            dialog.dismiss()
            showEntryDialog(record.type, record)
        }
        view.findViewById<Button>(R.id.deleteButton).setOnClickListener {
            dialog.dismiss()
            confirmArchive(record)
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

    private fun confirmArchive(record:Record) {
        if(loading || mutating || archivedVehicle) return
        val entry=record.toEntry()
        if(entry.isArchived) { confirmEntryArchive(entry,false);return }
        AlertDialog.Builder(this)
            .setTitle(R.string.auto_event_actions)
            .setItems(arrayOf(getString(R.string.auto_archive),getString(R.string.auto_cancel_event))) { _,index ->
                if(index==0) confirmEntryArchive(entry,true) else confirmCancelEntry(entry)
            }.setNegativeButton(android.R.string.cancel,null).show()
    }

    private fun confirmEntryArchive(entry:AutoEntry,archived:Boolean) {
        AlertDialog.Builder(this)
            .setTitle(if(archived) R.string.auto_archive else R.string.auto_restore)
            .setMessage(if(archived) R.string.auto_archive_event_message else R.string.auto_restore_event_message)
            .setPositiveButton(if(archived) R.string.auto_archive else R.string.auto_restore) { _,_ ->
                journalModel.execute(AutoJournalViewModel.Command.EntryArchive(entry.type,entry.id,archived))
            }.setNegativeButton(android.R.string.cancel,null).show()
    }

    private fun confirmCancelEntry(entry:AutoEntry) {
        AlertDialog.Builder(this)
            .setTitle(R.string.auto_cancel_event).setMessage(R.string.auto_cancel_event_message)
            .setPositiveButton(R.string.auto_cancel_confirm) { _,_ ->
                journalModel.execute(AutoJournalViewModel.Command.Cancel(entry))
            }.setNegativeButton(android.R.string.cancel,null).show()
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
            holder.title.text = record.title.ifBlank { ctx.getString(record.type.labelRes) }.let {
                if(record.isArchived) ctx.getString(R.string.auto_archived_name,it) else it
            }
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
