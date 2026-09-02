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
import com.github.mikephil.charting.charts.BarChart
import com.github.mikephil.charting.charts.BarLineChartBase
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.charts.PieChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.BarData
import com.github.mikephil.charting.data.BarDataSet
import com.github.mikephil.charting.data.BarEntry
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.data.PieData
import com.github.mikephil.charting.data.PieDataSet
import com.github.mikephil.charting.data.PieEntry
import com.github.mikephil.charting.formatter.PercentFormatter
import com.github.mikephil.charting.formatter.ValueFormatter
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
    private lateinit var flowBarChart: BarChart
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
    private var compareEnabled = false
    private val dateBtnFormat = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault())
    private val compareDateFormat = SimpleDateFormat("d.MM.yyyy", Locale("ru"))

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
                menu.add(0, 4, 1, getString(R.string.participants_report_title))
                menu.add(0, 2, 2, getString(R.string.stats_export_csv))
                menu.add(0, 3, 3, getString(R.string.stats_export_pdf))
                setOnMenuItemClickListener { item ->
                    when (item.itemId) {
                        1 -> startActivity(Intent(this@StatisticsActivity, PlanFactActivity::class.java))
                        4 -> startActivity(Intent(this@StatisticsActivity, ParticipantsReportActivity::class.java))
                        2 -> launchCsvExport()
                        3 -> launchPdfExport()
                    }
                    true
                }
                show()
            }
        }
        lineChart = findViewById(R.id.balanceLineChart)
        flowBarChart = findViewById(R.id.flowBarChart)
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
        bindCompareChip()
        bindChartModeChips()
        bindPieGroupChips()
        loadStats()
    }

    override fun onResume() {
        super.onResume()
        loadStats()
    }

    private fun setupCharts() {
        setupLineStyleChart(lineChart)
        setupLineStyleChart(flowBarChart)
        pieChart.description.isEnabled = false
        pieChart.setUsePercentValues(true)
        pieChart.setDrawEntryLabels(false)
        pieChart.legend.isEnabled = false
        pieChart.setHoleColor(Color.TRANSPARENT)
    }

    private fun setupLineStyleChart(chart: BarLineChartBase<*>) {
        chart.description.isEnabled = false
        chart.legend.isEnabled = false
        chart.axisRight.isEnabled = false
        chart.xAxis.position = XAxis.XAxisPosition.BOTTOM
        chart.xAxis.setDrawGridLines(false)
        chart.setTouchEnabled(false)
        chart.axisLeft.textColor = ContextCompat.getColor(this, R.color.text_secondary)
        chart.xAxis.textColor = ContextCompat.getColor(this, R.color.text_secondary)
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

    private fun bindCompareChip() {
        val chip = findViewById<TextView>(R.id.statsCompareChip)
        fun refresh() {
            chip.isSelected = compareEnabled
        }
        chip.setOnClickListener {
            compareEnabled = !compareEnabled
            refresh()
            loadStats()
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
            bindComparison(snapshot)
            bindChart(snapshot)
            bindPie(snapshot)
            findViewById<TextView>(R.id.summaryText).apply {
                visibility = View.VISIBLE
                text = periodLabel()
            }
        }
    }

    private suspend fun computeSnapshot(range: Pair<Long, Long>? = null): StatsSnapshot = withContext(Dispatchers.IO) {
        val (from, to) = range ?: rangeMillis()
        val dayMs = TimeUnit.DAYS.toMillis(1)
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
        val currentBalance = if (selectedBudgetId == null) {
            manager.getTotalBalanceAll()
        } else {
            manager.getTotalBalance(selectedBudgetId!!)
        }
        val fromDay = from / dayMs
        val toDay = to / dayMs
        val snapshotsByDay = if (selectedBudgetId == null) {
            manager.repository.getAllBalanceSnapshotsInRange(fromDay, toDay)
                .groupBy { it.dayKey }
                .mapValues { (_, list) -> list.sumOf { it.totalBalance } }
        } else {
            manager.repository.getBalanceSnapshotsForBudget(selectedBudgetId!!, fromDay, toDay)
                .associate { it.dayKey to it.totalBalance }
        }
        StatsSnapshot(inBudget, inPeriod, categories, from, to, currentBalance, snapshotsByDay)
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
                val chartExport = buildChartExport(snapshot)
                val comparison = buildComparisonBlock(snapshot)
                val data = MonthReportPdfExporter.ReportData(
                    periodLabel = label,
                    budgetName = budgetName,
                    totalIncome = income,
                    totalExpense = expense,
                    expensesByCategory = slices,
                    transactionCount = snapshot.inPeriod.size,
                    currentBalance = snapshot.currentBalance,
                    balanceStart = chartExport.balanceStart,
                    balanceEnd = chartExport.balanceEnd,
                    chartMode = chartExport.mode,
                    chartValues = chartExport.values,
                    comparison = comparison,
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

    private data class ChartExportData(
        val mode: MonthReportPdfExporter.ChartMode,
        val values: List<Double>,
        val balanceStart: Double?,
        val balanceEnd: Double?,
    )

    private fun buildChartExport(snapshot: StatsSnapshot): ChartExportData {
        return if (chartMode == ChartMode.BALANCE) {
            val series = StatisticsChartHelper.buildBalanceSeries(
                transactions = snapshot.allInBudget,
                fromMs = snapshot.from,
                toMs = snapshot.to,
                currentBalance = snapshot.currentBalance,
                snapshotsByDay = snapshot.snapshotsByDay,
            )
            ChartExportData(
                mode = MonthReportPdfExporter.ChartMode.BALANCE,
                values = series.points.map { it.value },
                balanceStart = series.startBalance,
                balanceEnd = series.endBalance,
            )
        } else {
            val series = StatisticsChartHelper.buildDailyFlowSeries(
                transactions = snapshot.allInBudget,
                fromMs = snapshot.from,
                toMs = snapshot.to,
            )
            ChartExportData(
                mode = MonthReportPdfExporter.ChartMode.FLOW,
                values = series.points.map { it.value },
                balanceStart = null,
                balanceEnd = null,
            )
        }
    }

    private fun buildComparisonBlock(snapshot: StatsSnapshot): MonthReportPdfExporter.ComparisonBlock? {
        val (prevFrom, prevTo) = StatisticsPeriodComparisonHelper.previousRange(snapshot.from, snapshot.to)
        val previousTx = snapshot.allInBudget.filter { it.date in prevFrom..prevTo }
        val label = getString(
            R.string.stats_compare_period,
            compareDateFormat.format(Date(prevFrom)),
            compareDateFormat.format(Date(prevTo)),
        )
        return MonthReportPdfExporter.ComparisonBlock(
            previousPeriodLabel = label,
            comparison = StatisticsPeriodComparisonHelper.buildComparison(
                currentTransactions = snapshot.inPeriod,
                previousTransactions = previousTx,
                previousPeriodLabel = label,
            ),
        )
    }

    private fun bindComparison(snapshot: StatsSnapshot) {
        val card = findViewById<View>(R.id.statsComparisonCard)
        if (!compareEnabled) {
            card.visibility = View.GONE
            return
        }
        val (prevFrom, prevTo) = StatisticsPeriodComparisonHelper.previousRange(snapshot.from, snapshot.to)
        val previousTx = snapshot.allInBudget.filter { it.date in prevFrom..prevTo }
        val label = getString(
            R.string.stats_compare_period,
            compareDateFormat.format(Date(prevFrom)),
            compareDateFormat.format(Date(prevTo)),
        )
        val comparison = StatisticsPeriodComparisonHelper.buildComparison(
            currentTransactions = snapshot.inPeriod,
            previousTransactions = previousTx,
            previousPeriodLabel = label,
        )
        card.visibility = View.VISIBLE
        findViewById<TextView>(R.id.statsComparePeriodLabel).text = label
        findViewById<TextView>(R.id.statsCompareIncome).text =
            formatCompareLine(R.string.stats_kpi_income, comparison, useSaldo = false, isExpense = false)
        findViewById<TextView>(R.id.statsCompareExpense).text =
            formatCompareLine(R.string.stats_kpi_expense, comparison, useSaldo = false, isExpense = true)
        findViewById<TextView>(R.id.statsCompareSaldo).text =
            formatCompareLine(R.string.stats_kpi_balance, comparison, useSaldo = true, isExpense = false)
    }

    private fun formatCompareLine(
        labelRes: Int,
        comparison: StatisticsPeriodComparisonHelper.Comparison,
        useSaldo: Boolean,
        isExpense: Boolean,
    ): String {
        val current = when {
            useSaldo -> comparison.current.saldo
            isExpense -> comparison.current.expense
            else -> comparison.current.income
        }
        val previous = when {
            useSaldo -> comparison.previous.saldo
            isExpense -> comparison.previous.expense
            else -> comparison.previous.income
        }
        val delta = when {
            useSaldo -> comparison.saldoDelta
            isExpense -> comparison.expenseDelta
            else -> comparison.incomeDelta
        }
        val deltaText = StatisticsPeriodComparisonHelper.formatDeltaAmount(delta)
        val pctText = StatisticsPeriodComparisonHelper.formatDeltaPercent(delta, previous)?.let { " ($it)" }.orEmpty()
        return getString(
            R.string.stats_compare_row,
            getString(labelRes),
            MoneyFormat.formatRub(current),
            MoneyFormat.formatRub(previous),
            deltaText + pctText,
        )
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
        findViewById<TextView>(R.id.statsCurrentBalance).text = MoneyFormat.formatRub(snapshot.currentBalance)
        val color = when {
            diff < -0.005 -> R.color.expense_red
            diff > 0.005 -> R.color.income_green
            else -> R.color.budget_blue
        }
        findViewById<TextView>(R.id.statsKpiBalance).setTextColor(ContextCompat.getColor(this, color))
    }

    private fun bindChart(snapshot: StatsSnapshot) {
        val empty = findViewById<TextView>(R.id.statisticsEmptyText)
        empty.visibility = View.GONE
        if (chartMode == ChartMode.BALANCE) {
            lineChart.visibility = View.VISIBLE
            flowBarChart.visibility = View.GONE
            bindBalanceChart(snapshot)
        } else {
            lineChart.visibility = View.GONE
            flowBarChart.visibility = View.VISIBLE
            bindFlowChart(snapshot)
        }
    }

    private fun bindBalanceChart(snapshot: StatsSnapshot) {
        val series = StatisticsChartHelper.buildBalanceSeries(
            transactions = snapshot.allInBudget,
            fromMs = snapshot.from,
            toMs = snapshot.to,
            currentBalance = snapshot.currentBalance,
            snapshotsByDay = snapshot.snapshotsByDay,
        )
        findViewById<TextView>(R.id.statsBalanceChartSubtitle).text = getString(
            R.string.stats_chart_balance_subtitle,
            MoneyFormat.formatRub(series.startBalance),
            MoneyFormat.formatRub(series.endBalance),
        )
        val dayKeys = series.points.map { it.dayKey }
        val entries = series.points.mapIndexed { index, point ->
            Entry(index.toFloat(), point.value.toFloat())
        }
        val values = series.points.map { it.value }
        configureMoneyAxis(lineChart, values)
        configureDayAxis(lineChart, dayKeys)
        val set = LineDataSet(entries, "").apply {
            color = ContextCompat.getColor(this@StatisticsActivity, R.color.budget_blue)
            setDrawCircles(false)
            setDrawValues(false)
            lineWidth = 2f
            setDrawFilled(true)
            fillColor = ContextCompat.getColor(this@StatisticsActivity, R.color.primary_green_light)
            mode = LineDataSet.Mode.LINEAR
        }
        lineChart.data = LineData(set)
        lineChart.invalidate()
    }

    private fun bindFlowChart(snapshot: StatsSnapshot) {
        findViewById<TextView>(R.id.statsBalanceChartSubtitle).text =
            getString(R.string.stats_chart_flow_subtitle)
        val series = StatisticsChartHelper.buildDailyFlowSeries(
            transactions = snapshot.allInBudget,
            fromMs = snapshot.from,
            toMs = snapshot.to,
        )
        val dayKeys = series.points.map { it.dayKey }
        val incomeColor = ContextCompat.getColor(this, R.color.income_green)
        val expenseColor = ContextCompat.getColor(this, R.color.expense_red)
        val entries = series.points.mapIndexed { index, point ->
            BarEntry(index.toFloat(), point.value.toFloat())
        }
        val barColors = series.points.map { point ->
            if (point.value >= 0.0) incomeColor else expenseColor
        }
        val values = series.points.map { it.value }
        configureMoneyAxis(flowBarChart, values, includeZero = true)
        configureDayAxis(flowBarChart, dayKeys)
        val set = BarDataSet(entries, "").apply {
            colors = barColors
            setDrawValues(false)
        }
        flowBarChart.data = BarData(set).apply { barWidth = 0.65f }
        flowBarChart.invalidate()
    }

    private fun configureMoneyAxis(
        chart: BarLineChartBase<*>,
        values: List<Double>,
        includeZero: Boolean = false,
    ) {
        val axisValues = if (includeZero) values + listOf(0.0) else values
        val (yMin, yMax) = StatisticsChartHelper.yAxisBounds(axisValues)
        chart.axisLeft.apply {
            setDrawGridLines(true)
            gridColor = ContextCompat.getColor(this@StatisticsActivity, R.color.chart_grid)
            valueFormatter = MoneyAxisFormatter()
            axisMinimum = yMin
            axisMaximum = yMax
            setDrawLabels(true)
        }
    }

    private fun configureDayAxis(chart: BarLineChartBase<*>, dayKeys: List<Long>) {
        if (dayKeys.isEmpty()) {
            chart.xAxis.setDrawLabels(false)
            return
        }
        chart.xAxis.apply {
            granularity = 1f
            setLabelCount(StatisticsChartHelper.xLabelCount(dayKeys.size), false)
            valueFormatter = DayAxisFormatter(dayKeys, dateBtnFormat)
            setDrawLabels(dayKeys.size <= 90)
        }
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

    private fun parseColor(hex: String, index: Int): Int {
        if (hex.isNotBlank()) {
            runCatching { return Color.parseColor(hex) }
        }
        val palette = ColorTemplate.MATERIAL_COLORS
        return palette[index % palette.size]
    }

    private class MoneyAxisFormatter : ValueFormatter() {
        override fun getFormattedValue(value: Float): String =
            MoneyFormat.formatChartAxis(value.toDouble())
    }

    private class DayAxisFormatter(
        private val dayKeys: List<Long>,
        private val format: SimpleDateFormat,
    ) : ValueFormatter() {
        override fun getFormattedValue(value: Float): String {
            if (dayKeys.isEmpty()) return ""
            val index = value.toInt().coerceIn(0, dayKeys.lastIndex)
            return format.format(Date(StatisticsChartHelper.dayKeyToMillis(dayKeys[index])))
        }
    }

    private data class StatsSnapshot(
        val allInBudget: List<TransactionEntity>,
        val inPeriod: List<TransactionEntity>,
        val categories: List<BudgetCategory>,
        val from: Long,
        val to: Long,
        val currentBalance: Double,
        val snapshotsByDay: Map<Long, Double>,
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
