package ru.mybudget.app.reports

import android.content.Context
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import ru.mybudget.app.MoneyFormat
import ru.mybudget.app.R
import ru.mybudget.app.YearOverviewHelper
import java.io.OutputStream

object YearReportPdfExporter {
    fun write(context: Context, overview: YearOverviewHelper.YearOverview, output: OutputStream) {
        val doc = PdfDocument()
        try {
            val writer = PageWriter(doc)
            writer.drawTitle(context.getString(R.string.year_overview_pdf_title, overview.year))
            writer.drawHeader(context.getString(R.string.report_pdf_summary))
            writer.drawBody(context.getString(R.string.report_pdf_income, MoneyFormat.formatRub(overview.totalIncome)))
            writer.drawBody(context.getString(R.string.report_pdf_expense, MoneyFormat.formatRub(overview.totalExpense)))
            writer.drawBody(context.getString(R.string.report_pdf_balance, MoneyFormat.formatRub(overview.totalSaldo)))
            writer.drawBody(context.getString(R.string.report_pdf_transactions, overview.transactionCount))
            writer.space(8f)
            writer.drawHeader(context.getString(R.string.year_overview_months_title))
            for (month in overview.months) {
                if (month.income <= 0.0 && month.expense <= 0.0) continue
                writer.drawBody(
                    context.getString(
                        R.string.year_overview_month_line,
                        month.label,
                        MoneyFormat.formatRub(month.income),
                        MoneyFormat.formatRub(month.expense),
                        MoneyFormat.formatRub(month.saldo),
                    ),
                )
            }
            if (overview.topExpenses.isNotEmpty()) {
                writer.space(8f)
                writer.drawHeader(context.getString(R.string.month_comparison_top))
                for (row in overview.topExpenses) {
                    writer.drawBody("${row.name}: ${MoneyFormat.formatRub(row.amount)}")
                }
            }
            writer.finish()
            doc.writeTo(output)
        } finally {
            doc.close()
        }
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
        private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 12f }

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

        fun space(amount: Float) {
            y += amount
        }

        fun ensureSpace(needed: Float) {
            if (y + needed > 780f) startNewPage()
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
