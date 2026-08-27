package ru.mybudget.app

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.mybudget.app.data.UtilityMeterReadingEntity
import ru.mybudget.app.setup.ActivePropertyPreferences
import ru.mybudget.app.utilities.MeterDateParser
import ru.mybudget.app.utilities.MeterRepository
import ru.mybudget.app.utilities.UtilityMeterDialogs

class UtilityMeterHistoryActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_GROUP = "group"
        const val EXTRA_METER = "meter"
    }

    private lateinit var repository: MeterRepository
    private lateinit var adapter: HistoryAdapter
    private var groupName = ""
    private var meterName = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_utility_meter_history)
        groupName = intent.getStringExtra(EXTRA_GROUP).orEmpty()
        meterName = intent.getStringExtra(EXTRA_METER).orEmpty()
        if (meterName.isBlank()) {
            finish()
            return
        }
        ScreenHeaderHelper.setup(this, meterName)
        findViewById<TextView>(R.id.meterHistorySubtitle).text = if (groupName.isBlank()) {
            getString(R.string.meter_history_subtitle_no_group)
        } else {
            groupName
        }
        repository = MeterRepository(
            BudgetManager.getInstance(this).utilityDao,
            ActivePropertyPreferences.getActivePropertyId(this),
        )
        adapter = HistoryAdapter { confirmDeleteReading(it) }
        findViewById<RecyclerView>(R.id.meterHistoryRecycler).apply {
            layoutManager = LinearLayoutManager(this@UtilityMeterHistoryActivity)
            adapter = this@UtilityMeterHistoryActivity.adapter
        }
        findViewById<View>(R.id.meterHistoryAddButton).setOnClickListener {
            UtilityMeterDialogs.showAddReading(
                this,
                lifecycleScope,
                repository,
                groupName,
                meterName,
            ) { loadHistory() }
        }
        loadHistory()
    }

    override fun onResume() {
        super.onResume()
        if (meterName.isNotBlank()) loadHistory()
    }

    private fun loadHistory() {
        lifecycleScope.launch {
            val items = withContext(Dispatchers.IO) { repository.getHistory(groupName, meterName) }
            adapter.submit(items)
            val empty = items.isEmpty()
            findViewById<View>(R.id.meterHistoryEmpty).visibility = if (empty) View.VISIBLE else View.GONE
            findViewById<View>(R.id.meterHistoryRecycler).visibility = if (empty) View.GONE else View.VISIBLE
        }
    }

    private fun confirmDeleteReading(reading: UtilityMeterReadingEntity) {
        AlertDialog.Builder(this)
            .setTitle(R.string.meter_reading_delete_title)
            .setMessage(R.string.meter_reading_delete_confirm)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    repository.deleteMeterReading(reading.id)
                    withContext(Dispatchers.Main) { loadHistory() }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private class HistoryAdapter(
        private val onLongPressDelete: (UtilityMeterReadingEntity) -> Unit,
    ) : RecyclerView.Adapter<HistoryAdapter.Holder>() {
        private var items: List<UtilityMeterReadingEntity> = emptyList()

        fun submit(list: List<UtilityMeterReadingEntity>) {
            items = list
            notifyDataSetChanged()
        }

        class Holder(v: View) : RecyclerView.ViewHolder(v) {
            val period: TextView = v.findViewById(R.id.meterPeriod)
            val values: TextView = v.findViewById(R.id.meterValues)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_utility_meter, parent, false)
            v.findViewById<View>(R.id.meterTitle).visibility = View.GONE
            v.findViewById<View>(R.id.meterVerification).visibility = View.GONE
            return Holder(v)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = items[position]
            holder.period.text = MeterDateParser.formatPeriodLabelForDisplay(item.periodLabel)
            val cons = item.consumption?.let { " · расход ${MoneyFormat.format(it)}" } ?: ""
            holder.values.text = "Показание: ${MoneyFormat.format(item.readingValue)}$cons"
            holder.itemView.setOnLongClickListener {
                onLongPressDelete(item)
                true
            }
        }

        override fun getItemCount(): Int = items.size
    }
}
