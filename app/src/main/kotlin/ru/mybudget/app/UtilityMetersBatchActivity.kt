package ru.mybudget.app

import android.app.DatePickerDialog
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
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
import ru.mybudget.app.setup.ActivePropertyPreferences
import ru.mybudget.app.utilities.MeterBatchEntry
import ru.mybudget.app.utilities.MeterBatchSaveFailure
import ru.mybudget.app.utilities.MeterCatalogSummary
import ru.mybudget.app.utilities.MeterDateParser
import ru.mybudget.app.utilities.MeterRepository
import java.time.LocalDate

class UtilityMetersBatchActivity : AppCompatActivity() {
    private lateinit var repository: MeterRepository
    private lateinit var adapter: BatchMeterAdapter
    private var selectedEpochDay = LocalDate.now().toEpochDay()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_utility_meters_batch)
        ScreenHeaderHelper.setup(this, getString(R.string.meter_batch_title), getString(R.string.utility_icon_batch))
        repository = MeterRepository(
            BudgetManager.getInstance(this).utilityDao,
            ActivePropertyPreferences.getActivePropertyId(this),
        )
        val dateInput = findViewById<EditText>(R.id.batchDateInput)
        val recycler = findViewById<RecyclerView>(R.id.batchMetersRecycler)
        val emptyText = findViewById<View>(R.id.batchEmptyText)
        val saveButton = findViewById<View>(R.id.batchSaveButton)
        adapter = BatchMeterAdapter()
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter
        refreshDateField(dateInput)
        dateInput.setOnClickListener { openDatePicker(dateInput) }
        saveButton.setOnClickListener { saveAll() }
        lifecycleScope.launch {
            val summaries = withContext(Dispatchers.IO) { repository.getMeterCatalogSummaries() }
            adapter.submit(summaries)
            val empty = summaries.isEmpty()
            emptyText.visibility = if (empty) View.VISIBLE else View.GONE
            recycler.visibility = if (empty) View.GONE else View.VISIBLE
            saveButton.isEnabled = !empty
        }
    }

    private fun refreshDateField(dateInput: EditText) {
        dateInput.setText(MeterDateParser.formatEpochDay(selectedEpochDay))
    }

    private fun openDatePicker(dateInput: EditText) {
        val initial = LocalDate.ofEpochDay(selectedEpochDay)
        DatePickerDialog(
            this,
            { _, year, month, day ->
                selectedEpochDay = LocalDate.of(year, month + 1, day).toEpochDay()
                refreshDateField(dateInput)
            },
            initial.year,
            initial.monthValue - 1,
            initial.dayOfMonth,
        ).show()
    }

    private fun saveAll() {
        val entries = adapter.collectEntries()
        if (entries.isEmpty()) {
            Toast.makeText(this, R.string.meter_batch_nothing_to_save, Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                repository.addMeterReadingsBatch(selectedEpochDay, entries)
            }
            when {
                result.failures.isEmpty() -> {
                    Toast.makeText(
                        this@UtilityMetersBatchActivity,
                        getString(R.string.meter_batch_saved_ok, result.saved),
                        Toast.LENGTH_SHORT,
                    ).show()
                    finish()
                }
                result.saved > 0 -> showPartialResultDialog(result.saved, result.failures)
                else -> showErrorsDialog(result.failures)
            }
        }
    }

    private fun formatFailure(failure: MeterBatchSaveFailure): String {
        val message = MeterRepository.messageFor(failure.result)?.let { getString(it) }.orEmpty()
        return "${failure.meterName}: $message"
    }

    private fun showPartialResultDialog(saved: Int, failures: List<MeterBatchSaveFailure>) {
        val lines = failures.joinToString("\n") { formatFailure(it) }
        AlertDialog.Builder(this)
            .setTitle(R.string.meter_batch_partial_title)
            .setMessage("${getString(R.string.meter_batch_partial_message, saved)}\n\n$lines")
            .setPositiveButton(android.R.string.ok) { _, _ -> finish() }
            .show()
    }

    private fun showErrorsDialog(failures: List<MeterBatchSaveFailure>) {
        AlertDialog.Builder(this)
            .setTitle(R.string.meter_batch_errors_title)
            .setMessage(failures.joinToString("\n") { formatFailure(it) })
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private class BatchMeterAdapter : RecyclerView.Adapter<BatchMeterAdapter.Holder>() {
        private var items: List<MeterCatalogSummary> = emptyList()
        private val draftValues = linkedMapOf<String, String>()

        fun submit(list: List<MeterCatalogSummary>) {
            items = list
            draftValues.clear()
            notifyDataSetChanged()
        }

        fun collectEntries(): List<MeterBatchEntry> {
            return items.mapNotNull { item ->
                val raw = draftValues[meterKey(item.info.groupName, item.info.meterName)]
                    ?.trim()
                    ?.replace(',', '.')
                    .orEmpty()
                val value = raw.toDoubleOrNull() ?: return@mapNotNull null
                MeterBatchEntry(item.info.groupName, item.info.meterName, value)
            }
        }

        private fun meterKey(groupName: String, meterName: String) =
            "${groupName.trim()}\u0001${meterName.trim()}"

        class Holder(v: View) : RecyclerView.ViewHolder(v) {
            val name: TextView = v.findViewById(R.id.batchMeterName)
            val group: TextView = v.findViewById(R.id.batchMeterGroup)
            val value: EditText = v.findViewById(R.id.batchMeterValue)
            var boundKey: String = ""
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_batch_meter_reading, parent, false)
            val holder = Holder(v)
            holder.value.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    if (holder.boundKey.isNotEmpty()) {
                        draftValues[holder.boundKey] = s?.toString().orEmpty()
                    }
                }
            })
            return holder
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = items[position]
            val info = item.info
            holder.name.text = info.meterName.trim()
            if (info.groupName.isBlank()) {
                holder.group.visibility = View.GONE
            } else {
                holder.group.visibility = View.VISIBLE
                holder.group.text = info.groupName
            }
            val key = meterKey(info.groupName, info.meterName)
            holder.boundKey = key
            val last = item.lastReading
            holder.value.hint = if (last != null) {
                holder.itemView.context.getString(
                    R.string.meter_batch_last_hint,
                    MeterDateParser.formatPeriodLabelForDisplay(last.periodLabel),
                    MoneyFormat.format(last.readingValue),
                )
            } else {
                holder.itemView.context.getString(R.string.meter_batch_value_hint)
            }
            holder.value.setText(draftValues[key].orEmpty())
        }

        override fun getItemCount(): Int = items.size
    }
}
