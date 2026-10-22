package ru.mybudget.app

import android.app.DatePickerDialog
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.github.mikephil.charting.charts.BarChart
import com.github.mikephil.charting.charts.PieChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.BarData
import com.github.mikephil.charting.data.BarDataSet
import com.github.mikephil.charting.data.BarEntry
import com.github.mikephil.charting.data.PieData
import com.github.mikephil.charting.data.PieDataSet
import com.github.mikephil.charting.data.PieEntry
import com.github.mikephil.charting.formatter.IndexAxisValueFormatter
import com.github.mikephil.charting.formatter.ValueFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.mybudget.app.auto.AutoEntry
import ru.mybudget.app.auto.AutoEntryType
import ru.mybudget.app.auto.AutoPaymentMethod
import ru.mybudget.app.auto.AutoStatisticsHelper
import ru.mybudget.app.data.AutoRepository
import java.time.LocalDate
import java.time.format.DateTimeFormatter

class AutoStatisticsActivity : AppCompatActivity() {
    private lateinit var results: LinearLayout
    private var entries = emptyList<AutoEntry>()
    private var vehicleNames = emptyMap<Int, String>()
    private var selectedVehicle: Int? = null
    private var period = 0
    private var customFrom = LocalDate.now().withDayOfMonth(1)
    private var customTo = LocalDate.now()
    private lateinit var customButton: Button
    private val dateFormat = DateTimeFormatter.ofPattern("dd.MM.yyyy")
    private val chartColors = listOf("#2196F3", "#9C27B0", "#FF9800", "#4CAF50").map { Color.parseColor(it) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_auto_statistics)
        ScreenHeaderHelper.setup(this, getString(R.string.auto_statistics_title))
        period = savedInstanceState?.getInt("auto_stats_period", 0) ?: 0
        customFrom = runCatching { LocalDate.parse(savedInstanceState?.getString("auto_stats_from")) }.getOrDefault(customFrom)
        customTo = runCatching { LocalDate.parse(savedInstanceState?.getString("auto_stats_to")) }.getOrDefault(customTo)
        val content = findViewById<LinearLayout>(R.id.autoStatsContent)
        val vehicleSpinner = Spinner(this)
        val periodSpinner = Spinner(this)
        customButton = layoutInflater.inflate(R.layout.item_module_action, content, false) as MaterialButton
        customButton.apply {
            setText(R.string.auto_stats_choose_dates)
            visibility = View.GONE
            setOnClickListener { chooseDates() }
        }
        content.addView(label(getString(R.string.auto_stats_vehicle)))
        content.addView(vehicleSpinner)
        content.addView(label(getString(R.string.auto_stats_period)))
        content.addView(periodSpinner)
        content.addView(customButton)
        results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(results)
        results.addView(label(getString(R.string.auto_stats_loading)))
        periodSpinner.adapter = ArrayAdapter(this, R.layout.item_operation_spinner,
            listOf(getString(R.string.auto_stats_month), getString(R.string.auto_stats_year),
                getString(R.string.auto_stats_all_time), getString(R.string.auto_stats_custom)))
        periodSpinner.setSelection(period)
        lifecycleScope.launch {
            try {
                val repository = AutoRepository(ru.mybudget.app.data.BudgetDatabase.getInstance(this@AutoStatisticsActivity))
                val data = withContext(Dispatchers.IO) { repository.getAllVehicles() to repository.getEntries() }
                entries = data.second
                val vehicles = data.first
                vehicleNames = vehicles.associate { it.id to if(it.isArchived) getString(R.string.auto_archived_name,it.name) else it.name }
                vehicleSpinner.adapter = ArrayAdapter(this@AutoStatisticsActivity,
                    R.layout.item_operation_spinner,
                    listOf(getString(R.string.auto_stats_all_vehicles)) + vehicles.map { vehicleNames.getValue(it.id) })
                val initial = savedInstanceState?.getInt("auto_stats_vehicle", 0) ?: intent.getIntExtra("vehicleId", 0)
                val index = vehicles.indexOfFirst { it.id == initial }
                vehicleSpinner.setSelection(if (index >= 0) index + 1 else 0)
                selectedVehicle = vehicles.getOrNull(index)?.id
                vehicleSpinner.onItemSelectedListener = selection { position ->
                    selectedVehicle = vehicles.getOrNull(position - 1)?.id
                    render()
                }
                periodSpinner.onItemSelectedListener = selection { position ->
                    period = position
                    customButton.visibility = if (position == 3) View.VISIBLE else View.GONE
                    render()
                }
                customButton.visibility = if (period == 3) View.VISIBLE else View.GONE
                render()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                results.removeAllViews()
                results.addView(label(getString(R.string.auto_stats_load_failed)))
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("auto_stats_period", period)
        outState.putInt("auto_stats_vehicle", selectedVehicle ?: 0)
        outState.putString("auto_stats_from", customFrom.toString())
        outState.putString("auto_stats_to", customTo.toString())
        super.onSaveInstanceState(outState)
    }

    private fun selection(block: (Int) -> Unit) = object : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = block(position)
        override fun onNothingSelected(parent: AdapterView<*>?) = Unit
    }

    private fun chooseDates() {
        fun pick(value: LocalDate, next: (LocalDate) -> Unit) {
            DatePickerDialog(this, { _, year, month, day -> next(LocalDate.of(year, month + 1, day)) },
                value.year, value.monthValue - 1, value.dayOfMonth).show()
        }
        pick(customFrom) { first ->
            pick(customTo) { last ->
                if (last < first) {
                    Toast.makeText(this, R.string.auto_stats_invalid_dates, Toast.LENGTH_LONG).show()
                } else {
                    customFrom = first
                    customTo = last
                    render()
                }
            }
        }
    }

    private fun render() {
        val today = LocalDate.now()
        val from = when (period) {
            0 -> today.withDayOfMonth(1)
            1 -> today.withDayOfYear(1)
            3 -> customFrom
            else -> null
        }
        val to = when (period) {
            0 -> today.withDayOfMonth(today.lengthOfMonth())
            1 -> LocalDate.of(today.year, 12, 31)
            3 -> customTo
            else -> null
        }
        customButton.text = "${customFrom.format(dateFormat)} — ${customTo.format(dateFormat)}"
        val summary = AutoStatisticsHelper.summarize(entries, selectedVehicle, from?.toEpochDay(), to?.toEpochDay())
        results.removeAllViews()
        val overview = layoutInflater.inflate(R.layout.include_module_summary, results, false)
        overview.findViewById<TextView>(R.id.moduleSummaryTitle).setText(R.string.design_auto_stat_paid)
        overview.findViewById<TextView>(R.id.moduleSummaryAmount).text = MoneyFormat.formatRub(summary.totalPaid)
        overview.findViewById<TextView>(R.id.moduleSummaryDetails).text = getString(R.string.auto_stats_payment_totals,
            MoneyFormat.formatRub(summary.fromBudget), MoneyFormat.formatRub(summary.cash), MoneyFormat.formatRub(summary.unpaid))
        results.addView(overview)
        results.addView(label(getString(R.string.auto_stats_date_hint)))
        results.addView(label(getString(R.string.auto_stats_archive_note)))
        if (summary.entries.isEmpty()) {
            results.addView(label(getString(R.string.auto_stats_empty)))
            return
        }
        results.addView(label(getString(R.string.auto_stats_breakdown), true))
        results.addView(label(getString(R.string.design_auto_stat_type_hint)))
        AutoEntryType.entries.forEach { type ->
            results.addView((layoutInflater.inflate(R.layout.item_module_action, results, false) as MaterialButton).apply {
                text = getString(R.string.auto_stats_type_total, typeLabel(type),
                    MoneyFormat.formatRub(summary.byType[type] ?: 0.0))
                setOnClickListener { showRecords(summary.entries.filter { it.type == type && it.paid }, typeLabel(type)) }
            })
        }
        if (summary.unpaid > 0.0) results.addView((layoutInflater.inflate(R.layout.item_module_action, results, false) as MaterialButton).apply {
            text = getString(R.string.design_auto_unpaid_records, MoneyFormat.formatRub(summary.unpaid))
            setOnClickListener { showRecords(summary.entries.filterNot { it.paid }, getString(R.string.auto_unpaid)) }
        })
        val paidTypes = AutoEntryType.entries.filter { (summary.byType[it] ?: 0.0) > 0.0 }
        if (paidTypes.isNotEmpty()) {
            val pie = PieChart(this).apply {
                layoutParams = LinearLayout.LayoutParams(-1, dp(260))
                description.isEnabled = false
                setNoDataText(getString(R.string.auto_stats_no_paid))
                setEntryLabelColor(ContextCompat.getColor(this@AutoStatisticsActivity, R.color.text_primary))
                legend.isEnabled = true
                legend.isWordWrapEnabled = true
                legend.textColor = ContextCompat.getColor(this@AutoStatisticsActivity, R.color.text_primary)
                setDrawEntryLabels(false)
                setHoleColor(ContextCompat.getColor(this@AutoStatisticsActivity, R.color.card_background))
                data = PieData(PieDataSet(paidTypes.map {
                    PieEntry(summary.byType.getValue(it).toFloat(), typeLabel(it))
                }, "").apply { colors = paidTypes.map { chartColors[it.ordinal] }; sliceSpace = 2f; setDrawValues(false) }).apply {
                    setValueTextSize(11f)
                    setValueFormatter(object : ValueFormatter() {
                        override fun getFormattedValue(value: Float) = MoneyFormat.format(value.toDouble())
                    })
                }
            }
            results.addView(pie)
            results.addView(label(getString(R.string.auto_stats_monthly), true))
            results.addView(BarChart(this).apply {
                layoutParams = LinearLayout.LayoutParams(-1, dp(260))
                description.isEnabled = false
                legend.isEnabled = false
                axisRight.isEnabled = false
                axisLeft.axisMinimum = 0f
                axisLeft.textColor = ContextCompat.getColor(this@AutoStatisticsActivity, R.color.text_secondary)
                axisLeft.valueFormatter = object : ValueFormatter() {
                    override fun getFormattedValue(value: Float) = MoneyFormat.formatChartAxis(value.toDouble())
                }
                xAxis.position = XAxis.XAxisPosition.BOTTOM
                xAxis.granularity = 1f
                xAxis.textColor = ContextCompat.getColor(this@AutoStatisticsActivity, R.color.text_secondary)
                xAxis.valueFormatter = IndexAxisValueFormatter(summary.byMonth.keys.map {
                    it.format(DateTimeFormatter.ofPattern("MM.yy"))
                })
                data = BarData(BarDataSet(summary.byMonth.values.mapIndexed { index, amount ->
                    BarEntry(index.toFloat(), amount.toFloat())
                }, "").apply { color = ContextCompat.getColor(this@AutoStatisticsActivity, R.color.md_theme_primary); setDrawValues(false) })
                setVisibleXRangeMaximum(12f)
                moveViewToX((summary.byMonth.size - 12).coerceAtLeast(0).toFloat())
            })
        } else results.addView(label(getString(R.string.auto_stats_no_paid)))
        if (summary.fuelLiters > 0.0) {
            results.addView(label(getString(R.string.auto_stats_fuel,
                MoneyFormat.formatQuantity(summary.fuelLiters),
                MoneyFormat.formatRub(summary.averageFuelPrice ?: 0.0))))
        }
    }

    private fun showRecords(records: List<AutoEntry>, title: String) {
        if (records.isEmpty()) {
            Toast.makeText(this, R.string.auto_stats_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val rows = records.sortedByDescending { it.dateEpochDay }.map {
            val payment = getString(when (it.paymentMethod) {
                AutoPaymentMethod.CASH -> R.string.auto_payment_cash
                AutoPaymentMethod.BUDGET -> R.string.auto_payment_budget
                else -> R.string.auto_unpaid
            })
            "${vehicleNames[it.vehicleId].orEmpty()} · ${LocalDate.ofEpochDay(it.dateEpochDay).format(dateFormat)} · ${MoneyFormat.formatRub(it.amount)}\n" +
                "${if(it.isArchived) getString(R.string.auto_archived_name,payment) else payment}${if (it.description.isBlank()) "" else "\n${it.description}"}"
        }
        AlertDialog.Builder(this).setTitle(title).setItems(rows.toTypedArray(), null)
            .setPositiveButton(android.R.string.ok, null).show()
    }

    private fun typeLabel(type: AutoEntryType) = getString(when (type) {
        AutoEntryType.SERVICE -> R.string.auto_section_service
        AutoEntryType.INSURANCE -> R.string.auto_section_insurance
        AutoEntryType.FUEL -> R.string.auto_section_fuel
        AutoEntryType.REPAIR -> R.string.auto_section_repair
    })

    private fun label(value: String, title: Boolean = false) = TextView(this).apply {
        text = value
        textSize = if (title) 19f else 14f
        setTextColor(ContextCompat.getColor(this@AutoStatisticsActivity, R.color.text_primary))
        setPadding(0, dp(12), 0, dp(8))
        if (title) setTypeface(typeface, android.graphics.Typeface.BOLD)
    }
    private fun dp(value: Int) = (resources.displayMetrics.density * value).toInt()
}
