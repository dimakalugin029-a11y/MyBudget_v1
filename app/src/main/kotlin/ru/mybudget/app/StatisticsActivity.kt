package ru.mybudget.app

import android.app.DatePickerDialog
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.charts.PieChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.data.PieData
import com.github.mikephil.charting.data.PieDataSet
import com.github.mikephil.charting.data.PieEntry
import com.github.mikephil.charting.formatter.PercentFormatter
import com.github.mikephil.charting.utils.ColorTemplate
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.mybudget.app.data.TransactionEntity
import ru.mybudget.app.reports.MonthReportPdfExporter
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class StatisticsActivity : AppCompatActivity() {
    private enum class Period { WEEK, MONTH, QUARTER, YEAR, CUSTOM }
    private enum class ChartMode { BALANCE, FLOW }
    private enum class PieGroup { PARENT, LEAF }

    private lateinit var manager: BudgetManager
    private lateinit var lineChart: LineChart
    private lateinit var pieChart: PieChart
    private lateinit var adapter: CategoryAdapter

    private var period = Period.MONTH
    private var chartMode = ChartMode.BALANCE
    private var pieGroup = PieGroup.PARENT
    private var selectedBudgetId: Int? = null
    private var customFrom = 0L
    private var customTo = 0L
    private var pieSlices: List<CategorySlice> = emptyList()
    private var lastSnapshot: StatsSnapshot? = null
    private val dateBtnFormat = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault())

    private val createDocLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv"),
    ) { uri -> if (uri != null) writeCsv(uri) }

    private val exportPdfLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf"),
    ) { uri -> if (uri != null) writePdf(uri) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_statistics)
        manager = BudgetManager.getInstance(this)
        ScreenHeaderHelper.setup(this, getString(R.string.statistics_title), getString(R.string.main_icon_statistics))
        findViewById<View>(R.id.statisticsHint)?.let {
            ScreenHintHelper.bind(this, it, ScreenHintHelper.Keys.STATISTICS, R.string.hint_statistics, showHelpLink = false)
        }
        ScreenHeaderHelper.bindAction(this, android.R.drawable.ic_menu_more, R.string.stats_more_menu) {
            PopupMenu(this, findViewById(R.id.screenHeaderAction)).apply {
                menu.add(0, 1, 0, getString(R.string.plan_fact_open))
                menu.add(0, 2, 1, getString(R.string.stats_export_csv))
                menu.add(0, 3, 2, getString(R.string.stats_export_pdf))
                setOnMenuItemClickListener { item ->
                    when (item.itemId) {
                        1 -> startActivity(Intent(this@StatisticsActivity, PlanFactActivity::class.java))
                        2 -> launchCsvExport()
                        3 -> launchPdfExport()
                    }
                    true
                }
                show()
            }
        }
        lineChart = findViewById(R.id.balanceLineChart)
        pieChart = findViewById(R.id.pieChart)
        setupCharts()
        adapter = CategoryAdapter { slice ->
            startActivity(
                Intent(this, TransactionsActivity::class.java)
                    .putExtra(TransactionsActivity.EXTRA_CATEGORY_IDS, slice.categoryIds.toIntArray())
                    .putExtra(TransactionsActivity.EXTRA_CATEGORY_TITLE, slice.name),
            )
        }
        findViewById<RecyclerView>(R.id.statsCategoryList).apply {
            layoutManager = LinearLayoutManager(this@StatisticsActivity)
            adapter = this@StatisticsActivity.adapter
        }
        findViewById<MaterialButton>(R.id.statsPlanFactButton).setOnClickListener {
            startActivity(Intent(this, PlanFactActivity::class.java))
        }
        findViewById<MaterialButton>(R.id.statsBudgetChip).setOnClickListener { pickBudget() }
        bindPeriodChips()
        bindChartModeChips()
        bindPieGroupChips()
        loadStats()
    }

    override fun onResume() {
        super.onResume()
        loadStats()
    }

    private fun setupCharts() {
        lineChart.description.isEnabled = false
        lineChart.legend.isEnabled = false
        lineChart.axisRight.isEnabled = false
        lineChart.xAxis.position = XAxis.XAxisPosition.BOTTOM
        lineChart.xAxis.setDrawGridLines(false)
        lineChart.xAxis.setDrawLabels(false)
        lineChart.setTouchEnabled(false)
        pieChart.description.isEnabled = false
        pieChart.setUsePercentValues(true)
        pieChart.setDrawEntryLabels(false)
        pieChart.legend.isEnabled = false
        pieChart.setHoleColor(Color.TRANSPARENT)
    }

    private fun bindPeriodChips() {
        val chips = mapOf(
            Period.WEEK to R.id.statsPeriodWeek,
            Period.MONTH to R.id.statsPeriodMonth,
            Period.QUARTER to R.id.statsPeriodQuarter,
            Period.YEAR to R.id.statsPeriodYear,
            Period.CUSTOM to R.id.statsPeriodCustom,
        )
        fun refresh() {
            chips.forEach { (value, id) -> findViewById<TextView>(id).isSelected = value == period }
        }
        chips.forEach { (value, id) ->
            findViewById<TextView>(id).setOnClickListener {
                if (value == Period.CUSTOM) {
                    pickCustomRange()
                } else {
                    period = value
                    refresh()
                    loadStats()
                }
            }
        }
        refresh()
    }

    private fun bindChartModeChips() {
        val balance = findViewById<TextView>(R.id.statsBalanceModeBalance)
        val flow = findViewById<TextView>(R.id.statsBalanceModeFlow)
        fun refresh() {
            balance.isSelected = chartMode == ChartMode.BALANCE
            flow.isSelected = chartMode == ChartMode.FLOW
        }
        balance.setOnClickListener {
            chartMode = ChartMode.BALANCE
            refresh()
            loadStats()
        }
        flow.setOnClickListener {
            chartMode = ChartMode.FLOW
            refresh()
            loadStats()
        }
        refresh()
    }

    private fun bindPieGroupChips() {
        val parent = findViewById<TextView>(R.id.statsPieGroupParent)
        val leaf = findViewById<TextView>(R.id.statsPieGroupLeaf)
        fun refresh() {
            parent.isSelected = pieGroup == PieGroup.PARENT
            leaf.isSelected = pieGroup == PieGroup.LEAF
        }
        parent.setOnClickListener {
            pieGroup = PieGroup.PARENT
            refresh()
            loadStats()
        }
        leaf.setOnClickListener {
            pieGroup = PieGroup.LEAF
            refresh()
            loadStats()
        }
        refresh()
    }

    private fun pickBudget() {
        lifecycleScope.launch {
            val profiles = manager.getBudgetProfilesAsync()
            val labels = mutableListOf(getString(R.string.stats_budget_all))
            labels += profiles.map { it.name }
            AlertDialog.Builder(this@StatisticsActivity)
                .setTitle(R.string.stats_budget_filter)
                .setItems(labels.toTypedArray()) { _, which ->
                    selectedBudgetId = if (which == 0) null else profiles[which - 1].id
                    findViewById<MaterialButton>(R.id.statsBudgetChip).text = labels[which]
                    loadStats()
                }
                .show()
        }
    }

    private fun pickCustomRange() {
        val fromCal = Calendar.getInstance()
        DatePickerDialog(this, { _, y1, m1, d1 ->
            fromCal.set(y1, m1, d1, 0, 0, 0)
            fromCal.set(Calendar.MILLISECOND, 0)
            val toCal = Calendar.getInstance()
            DatePickerDialog(this, { _, y2, m2, d2 ->
                toCal.set(y2, m2, d2, 23, 59, 59)
                customFrom = fromCal.timeInMillis
                customTo = toCal.timeInMillis
                period = Period.CUSTOM
                findViewById<TextView>(R.id.statsPeriodWeek).isSelected = false
                findViewById<TextView>(R.id.statsPeriodMonth).isSelected = false
                findViewById<TextView>(R.id.statsPeriodQuarter).isSelected = false
                findViewById<TextView>(R.id.statsPeriodYear).isSelected = false
                findViewById<TextView>(R.id.statsPeriodCustom).isSelected = true
                loadStats()
            }, toCal.get(Calendar.YEAR), toCal.get(Calendar.MONTH), toCal.get(Calendar.DAY_OF_MONTH)).show()
        }, fromCal.get(Calendar.YEAR), fromCal.get(Calendar.MONTH), fromCal.get(Calendar.DAY_OF_MONTH)).show()
    }

    private fun rangeMillis(): Pair<Long, Long> {
        val now = System.currentTimeMillis()
        if (period == Period.CUSTOM && customTo > customFrom) return customFrom to customTo
        val start = when (period) {
            Period.WEEK -> now - TimeUnit.DAYS.toMillis(7)
            Period.MONTH -> now - TimeUnit.DAYS.toMillis(30)
            Period.QUARTER -> now - TimeUnit.DAYS.toMillis(90)
            Period.YEAR -> now - TimeUnit.DAYS.toMillis(365)
            Period.CUSTOM -> now - TimeUnit.DAYS.toMillis(30)
        }
        return start to now
    }

    private fun periodLabel(): String {
        val fmt = SimpleDateFormat("d.MM", Locale("ru"))
        return when (period) {
            Period.WEEK -> getString(R.string.stats_period_week)
            Period.MONTH -> getString(R.string.stats_period_month)
            Period.QUARTER -> getString(R.string.stats_period_quarter)
            Period.YEAR -> getString(R.string.stats_period_year)
            Period.CUSTOM -> getString(R.string.stats_period_custom, fmt.format(Date(customFrom)), fmt.format(Date(customTo)))
        }
    }

    private fun loadStats() {
        lifecycleScope.launch {
            val snapshot = computeSnapshot()
            lastSnapshot = snapshot
            bindKpis(snapshot)
            bindLineChart(snapshot)
            bindPie(snapshot)
            findViewById<TextView>(R.id.summaryText).apply {
                visibility = View.VISIBLE
                text = periodLabel()
            }
        }
    }

    private suspend fun computeSnapshot(): StatsSnapshot = withContext(Dispatchers.IO) {
        val (from, to) = rangeMillis()
        manager.getCategoriesAsync()
        val allTx = manager.repository.getAllTransactions().first()
        val categories = manager.getCategories()
        val allowedIds = if (selectedBudgetId == null) {
            categories.map { it.id }.toSet()
        } else {
            manager.getCategoryIdsForBudget(selectedBudgetId!!)
        }
        val inBudget = allTx.filter { it.categoryId in allowedIds }
        val inPeriod = inBudget.filter { it.date in from..to }
        StatsSnapshot(inBudget, inPeriod, categories, from)
    }

    private fun launchCsvExport() {
        val (from, to) = rangeMillis()
        val name = when (period) {
            Period.WEEK -> "transactions_week.csv"
            Period.MONTH -> "transactions_month.csv"
            Period.QUARTER -> "transactions_3months.csv"
            Period.YEAR -> "transactions_year.csv"
            Period.CUSTOM -> "transactions_${dateBtnFormat.format(Date(from))}_${dateBtnFormat.format(Date(to))}.csv"
        }
        createDocLauncher.launch(name)
    }

    private fun launchPdfExport() {
        val name = when (period) {
            Period.WEEK -> "mybudget_week.pdf"
            Period.MONTH -> "mybudget_month.pdf"
            Period.QUARTER -> "mybudget_quarter.pdf"
            Period.YEAR -> "mybudget_year.pdf"
            Period.CUSTOM -> "mybudget_report.pdf"
        }
        exportPdfLauncher.launch(name)
    }

    private fun writeCsv(uri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val snapshot = lastSnapshot ?: computeSnapshot()
                val csv = buildExportCsv(snapshot)
                contentResolver.openOutputStream(uri)?.use { stream ->
                    stream.write(csv.toByteArray(Charsets.UTF_8))
                } ?: error("no stream")
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@StatisticsActivity, R.string.stats_export_done, Toast.LENGTH_SHORT).show()
                }
            } catch (_: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@StatisticsActivity, R.string.stats_export_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun writePdf(uri: Uri) {
        val slices = pieSlices.associate { it.name to it.amount }
        val label = periodLabel()
        val budgetName = findViewById<MaterialButton>(R.id.statsBudgetChip).text.toString()
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val snapshot = lastSnapshot ?: computeSnapshot()
                val income = snapshot.inPeriod.filter { it.type == "income" }.sumOf { it.amount }
                val expense = snapshot.inPeriod.filter { it.type == "expense" }.sumOf { it.amount }
                val data = MonthReportPdfExporter.ReportData(
                    periodLabel = label,
                    budgetName = budgetName,
                    totalIncome = income,
                    totalExpense = expense,
                    expensesByCategory = slices,
                    transactionCount = snapshot.inPeriod.size,
                )
                contentResolver.openOutputStream(uri)?.use { stream ->
                    MonthReportPdfExporter.write(this@StatisticsActivity, data, stream)
                } ?: error("no stream")
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@StatisticsActivity, R.string.stats_export_pdf_done, Toast.LENGTH_SHORT).show()
                }
            } catch (_: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@StatisticsActivity, R.string.stats_export_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun buildExportCsv(snapshot: StatsSnapshot): String {
        val names = snapshot.categories.associate { it.id to it.name }
        val df = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val sb = StringBuilder("date,category,type,amount,description\n")
        snapshot.inPeriod.forEach { tx ->
            val date = df.format(Date(tx.date))
            val category = names[tx.categoryId].orEmpty()
            val amount = String.format(Locale.getDefault(), "%.2f", tx.amount)
            val desc = tx.description.replace(",", " ")
            sb.append("$date,$category,${tx.type},$amount,$desc\n")
        }
        return sb.toString()
    }

    private fun bindKpis(snapshot: StatsSnapshot) {
        val income = snapshot.inPeriod.filter { it.type == "income" }.sumOf { it.amount }
        val expense = snapshot.inPeriod.filter { it.type == "expense" }.sumOf { it.amount }
        val diff = income - expense
        findViewById<TextView>(R.id.statsKpiIncome).text = MoneyFormat.formatRub(income)
        findViewById<TextView>(R.id.statsKpiExpense).text = MoneyFormat.formatRub(expense)
        findViewById<TextView>(R.id.statsKpiBalance).text = MoneyFormat.formatRub(diff)
        val color = when {
            diff < -0.005 -> R.color.expense_red
            diff > 0.005 -> R.color.income_green
            else -> R.color.budget_blue
        }
        findViewById<TextView>(R.id.statsKpiBalance).setTextColor(ContextCompat.getColor(this, color))
    }

    private fun bindLineChart(snapshot: StatsSnapshot) {
        val empty = findViewById<TextView>(R.id.statisticsEmptyText)
        if (snapshot.inPeriod.isEmpty()) {
            lineChart.visibility = View.GONE
            empty.visibility = View.VISIBLE
            lineChart.clear()
            return
        }
        empty.visibility = View.GONE
        lineChart.visibility = View.VISIBLE
        val dayMs = TimeUnit.DAYS.toMillis(1)
        val fromDay = snapshot.from / dayMs
        val toDay = System.currentTimeMillis() / dayMs
        val byDay = snapshot.inPeriod.groupBy { it.date / dayMs }
        var running = if (chartMode == ChartMode.BALANCE) {
            snapshot.allInBudget.filter { it.date < snapshot.from }.sumOf { signed(it) }
        } else {
            0.0
        }
        findViewById<TextView>(R.id.statsBalanceChartSubtitle).text = if (chartMode == ChartMode.BALANCE) {
            getString(R.string.stats_chart_balance_subtitle, MoneyFormat.formatRub(running))
        } else {
            getString(R.string.stats_chart_flow_subtitle)
        }
        val entries = mutableListOf<Entry>()
        var x = 0f
        var day = fromDay
        while (day <= toDay) {
            val dayTx = byDay[day].orEmpty()
            running += dayTx.sumOf { signed(it) }
            entries += Entry(x, running.toFloat())
            x += 1f
            day++
        }
        val set = LineDataSet(entries, "").apply {
            color = ContextCompat.getColor(this@StatisticsActivity, R.color.budget_blue)
            setDrawCircles(false)
            setDrawValues(false)
            lineWidth = 2f
            setDrawFilled(true)
            fillColor = ContextCompat.getColor(this@StatisticsActivity, R.color.primary_green_light)
            mode = LineDataSet.Mode.CUBIC_BEZIER
        }
        lineChart.data = LineData(set)
        lineChart.invalidate()
    }

    private fun bindPie(snapshot: StatsSnapshot) {
        val expenses = snapshot.inPeriod.filter { it.type == "expense" && it.amount > 0.0 }
        val grouped = linkedMapOf<Int, CategorySlice>()
        expenses.forEach { tx ->
            val category = snapshot.categories.firstOrNull { it.id == tx.categoryId } ?: return@forEach
            val keyCat = if (pieGroup == PieGroup.PARENT && category.parentId != 0) {
                snapshot.categories.firstOrNull { it.id == category.parentId } ?: category
            } else {
                category
            }
            val current = grouped[keyCat.id]
            val ids = (current?.categoryIds ?: emptyList()) + category.id
            grouped[keyCat.id] = CategorySlice(
                name = keyCat.name,
                amount = (current?.amount ?: 0.0) + tx.amount,
                color = parseColor(keyCat.colorHex, grouped.size),
                categoryIds = ids.distinct(),
            )
        }
        val total = grouped.values.sumOf { it.amount }
        val sorted = grouped.values.sortedByDescending { it.amount }
        pieSlices = sorted
        adapter.submit(sorted, total)
        if (sorted.isEmpty()) {
            pieChart.clear()
            pieChart.centerText = ""
            pieChart.invalidate()
            return
        }
        val entries = sorted.map { PieEntry(it.amount.toFloat(), it.name) }
        val dataSet = PieDataSet(entries, "").apply {
            colors = sorted.map { it.color }
            sliceSpace = 2f
            setDrawValues(false)
        }
        pieChart.data = PieData(dataSet).apply { setValueFormatter(PercentFormatter(pieChart)) }
        pieChart.centerText = getString(R.string.stats_expenses_center, MoneyFormat.formatRub(total))
        pieChart.invalidate()
    }

    private fun signed(tx: TransactionEntity): Double =
        if (tx.type == "income") tx.amount else -tx.amount

    private fun parseColor(hex: String, index: Int): Int {
        if (hex.isNotBlank()) {
            runCatching { return Color.parseColor(hex) }
        }
        val palette = ColorTemplate.MATERIAL_COLORS
        return palette[index % palette.size]
    }

    private data class StatsSnapshot(
        val allInBudget: List<TransactionEntity>,
        val inPeriod: List<TransactionEntity>,
        val categories: List<BudgetCategory>,
        val from: Long,
    )

    private data class CategorySlice(
        val name: String,
        val amount: Double,
        val color: Int,
        val categoryIds: List<Int>,
    )

    private class CategoryAdapter(
        private val onClick: (CategorySlice) -> Unit,
    ) : RecyclerView.Adapter<CategoryAdapter.Holder>() {
        private var items: List<CategorySlice> = emptyList()
        private var total = 0.0

        fun submit(data: List<CategorySlice>, sum: Double) {
            items = data
            total = sum
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_stats_category, parent, false)
            return Holder(view)
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = items[position]
            holder.color.setBackgroundColor(item.color)
            holder.name.text = item.name
            holder.amount.text = MoneyFormat.formatRub(item.amount)
            val percent = if (total > 0.0) (item.amount / total * 100.0) else 0.0
            holder.percent.text = String.format(Locale.getDefault(), "%.0f%%", percent)
            holder.itemView.setOnClickListener { onClick(item) }
        }

        class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val color: View = view.findViewById(R.id.statsCategoryColor)
            val name: TextView = view.findViewById(R.id.statsCategoryName)
            val percent: TextView = view.findViewById(R.id.statsCategoryPercent)
            val amount: TextView = view.findViewById(R.id.statsCategoryAmount)
        }
    }
}
