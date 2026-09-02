package ru.mybudget.app.reports

import android.content.Context
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import ru.mybudget.app.MoneyFormat
import ru.mybudget.app.R
import ru.mybudget.app.StatisticsPeriodComparisonHelper
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object MonthReportPdfExporter {
    enum class ChartMode { BALANCE, FLOW }

    data class ComparisonBlock(
        val previousPeriodLabel: String,
        val comparison: StatisticsPeriodComparisonHelper.Comparison,
    )

    data class ReportData(
        val periodLabel: String,
        val budgetName: String,
        val totalIncome: Double,
        val totalExpense: Double,
        val expensesByCategory: Map<String, Double>,
        val transactionCount: Int,
        val currentBalance: Double = 0.0,
        val balanceStart: Double? = null,
        val balanceEnd: Double? = null,
        val chartMode: ChartMode = ChartMode.BALANCE,
        val chartValues: List<Double> = emptyList(),
        val comparison: ComparisonBlock? = null,
    )

    fun write(context: Context, data: ReportData, output: OutputStream) {
        val doc = PdfDocument()
        try {
            val writer = PageWriter(doc)
            writer.drawTitle(context.getString(R.string.report_pdf_title))
            writer.drawMuted(data.periodLabel)
            writer.drawMuted(data.budgetName)
            writer.space(10f)
            writer.drawHeader(context.getString(R.string.report_pdf_summary))
            writer.drawBody(context.getString(R.string.report_pdf_income, MoneyFormat.formatRub(data.totalIncome)))
            writer.drawBody(context.getString(R.string.report_pdf_expense, MoneyFormat.formatRub(data.totalExpense)))
            val balance = data.totalIncome - data.totalExpense
            writer.drawBody(context.getString(R.string.report_pdf_balance, MoneyFormat.formatRub(balance)))
            writer.drawBody(context.getString(R.string.report_pdf_current_balance, MoneyFormat.formatRub(data.currentBalance)))
            if (data.balanceStart != null && data.balanceEnd != null) {
                writer.drawBody(
                    context.getString(
                        R.string.stats_chart_balance_subtitle,
                        MoneyFormat.formatRub(data.balanceStart),
                        MoneyFormat.formatRub(data.balanceEnd),
                    ),
                )
            }
            writer.drawBody(context.getString(R.string.report_pdf_transactions, data.transactionCount))
            data.comparison?.let { block ->
                writer.space(8f)
                writer.drawHeader(context.getString(R.string.stats_compare_title))
                writer.drawMuted(block.previousPeriodLabel)
                drawComparisonRows(context, writer, block.comparison)
            }
            if (data.chartValues.isNotEmpty()) {
                writer.space(8f)
                val chartTitle = if (data.chartMode == ChartMode.BALANCE) {
                    context.getString(R.string.stats_balance_chart_title)
                } else {
                    context.getString(R.string.stats_chart_mode_flow)
                }
                writer.drawHeader(chartTitle)
                writer.ensureSpace(170f)
                val top = writer.y
                val bottom = top + 150f
                if (data.chartMode == ChartMode.BALANCE) {
                    StatisticsPdfChartRenderer.drawBalanceChart(
                        canvas = writer.canvas,
                        left = 40f,
                        top = top,
                        right = 555f,
                        bottom = bottom,
                        values = data.chartValues,
                        lineColor = 0xFF2196F3.toInt(),
                        fillColor = 0xFFE8F5E8.toInt(),
                        gridColor = 0xFFE5E7EB.toInt(),
                        labelColor = 0xFF666666.toInt(),
                    )
                } else {
                    StatisticsPdfChartRenderer.drawFlowChart(
                        canvas = writer.canvas,
                        left = 40f,
                        top = top,
                        right = 555f,
                        bottom = bottom,
                        values = data.chartValues,
                        incomeColor = 0xFF4CAF50.toInt(),
                        expenseColor = 0xFFF44336.toInt(),
                        gridColor = 0xFFE5E7EB.toInt(),
                        labelColor = 0xFF666666.toInt(),
                    )
                }
                writer.y = bottom + 18f
            }
            writer.space(8f)
            writer.drawHeader(context.getString(R.string.report_pdf_top_expenses))
            val top = data.expensesByCategory.entries
                .sortedByDescending { it.value }
                .take(12)
            if (top.isEmpty()) {
                writer.drawMuted(context.getString(R.string.stats_no_data))
            } else {
                for (entry in top) {
                    writer.ensureSpace(16f)
                    writer.drawBody("${entry.key} — ${MoneyFormat.formatRub(entry.value)}")
                }
            }
            val generated = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date())
            writer.drawFooter(context.getString(R.string.report_pdf_generated, generated))
            writer.finish()
            doc.writeTo(output)
        } finally {
            doc.close()
        }
    }

    private fun drawComparisonRows(
        context: Context,
        writer: PageWriter,
        comparison: StatisticsPeriodComparisonHelper.Comparison,
    ) {
        writer.drawBody(formatCompareLine(context, R.string.stats_kpi_income, comparison, isExpense = false))
        writer.drawBody(formatCompareLine(context, R.string.stats_kpi_expense, comparison, isExpense = true))
        writer.drawBody(formatCompareLine(context, R.string.stats_kpi_balance, comparison, isExpense = false, useSaldo = true))
    }

    private fun formatCompareLine(
        context: Context,
        labelRes: Int,
        comparison: StatisticsPeriodComparisonHelper.Comparison,
        isExpense: Boolean,
        useSaldo: Boolean = false,
    ): String {
        val label = context.getString(labelRes)
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
        return context.getString(
            R.string.stats_compare_row,
            label,
            MoneyFormat.formatRub(current),
            MoneyFormat.formatRub(previous),
            deltaText + pctText,
        )
    }

    private class PageWriter(private val doc: PdfDocument) {
        private var pageNumber = 0
        private var currentPage: PdfDocument.Page? = null
        lateinit var canvas: android.graphics.Canvas
            private set
        var y = 48f

        private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 20f
            isFakeBoldText = true
        }
        private val headerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 14f
            isFakeBoldText = true
        }
        private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 12f
        }
        private val mutedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 11f
            color = 0xFF666666.toInt()
        }

        init {
            startNewPage()
        }

        fun drawTitle(text: String) {
            ensureSpace(24f)
            canvas.drawText(text, 40f, y, titlePaint)
            y += 28f
        }

        fun drawHeader(text: String) {
            ensureSpace(22f)
            canvas.drawText(text, 40f, y, headerPaint)
            y += 22f
        }

        fun drawBody(text: String) {
            ensureSpace(18f)
            canvas.drawText(text, 40f, y, bodyPaint)
            y += 18f
        }

        fun drawMuted(text: String) {
            ensureSpace(16f)
            canvas.drawText(text, 40f, y, mutedPaint)
            y += 16f
        }

        fun space(amount: Float) {
            y += amount
        }

        fun ensureSpace(needed: Float) {
            if (y + needed > 780f) {
                startNewPage()
            }
        }

        fun drawFooter(text: String) {
            canvas.drawText(text, 40f, 800f, mutedPaint)
        }

        fun finish() {
            currentPage?.let { doc.finishPage(it) }
            currentPage = null
        }

        private fun startNewPage() {
            currentPage?.let { doc.finishPage(it) }
            pageNumber++
            val page = doc.startPage(PdfDocument.PageInfo.Builder(595, 842, pageNumber).create())
            currentPage = page
            canvas = page.canvas
            y = 48f
        }
    }
}
