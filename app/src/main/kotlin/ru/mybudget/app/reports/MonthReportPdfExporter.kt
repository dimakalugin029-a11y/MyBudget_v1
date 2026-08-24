package ru.mybudget.app.reports

import android.content.Context
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import ru.mybudget.app.MoneyFormat
import ru.mybudget.app.R
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object MonthReportPdfExporter {
    data class ReportData(
        val periodLabel: String,
        val budgetName: String,
        val totalIncome: Double,
        val totalExpense: Double,
        val expensesByCategory: Map<String, Double>,
        val transactionCount: Int,
    )

    fun write(context: Context, data: ReportData, output: OutputStream) {
        val doc = PdfDocument()
        try {
            val page = doc.startPage(PdfDocument.PageInfo.Builder(595, 842, 1).create())
            val canvas = page.canvas
            val titlePaint = Paint().apply {
                isAntiAlias = true
                textSize = 20f
                isFakeBoldText = true
            }
            val headerPaint = Paint().apply {
                isAntiAlias = true
                textSize = 14f
                isFakeBoldText = true
            }
            val bodyPaint = Paint().apply {
                isAntiAlias = true
                textSize = 12f
            }
            val mutedPaint = Paint().apply {
                isAntiAlias = true
                textSize = 11f
                color = 0xFF666666.toInt()
            }
            canvas.drawText(context.getString(R.string.report_pdf_title), 40f, 48f, titlePaint)
            var y = 76f
            canvas.drawText(data.periodLabel, 40f, y, mutedPaint)
            y += 18f
            canvas.drawText(data.budgetName, 40f, y, mutedPaint)
            y += 28f
            canvas.drawText(context.getString(R.string.report_pdf_summary), 40f, y, headerPaint)
            y += 22f
            canvas.drawText(
                context.getString(R.string.report_pdf_income, MoneyFormat.formatRub(data.totalIncome)),
                40f,
                y,
                bodyPaint,
            )
            y += 18f
            canvas.drawText(
                context.getString(R.string.report_pdf_expense, MoneyFormat.formatRub(data.totalExpense)),
                40f,
                y,
                bodyPaint,
            )
            y += 18f
            val balance = data.totalIncome - data.totalExpense
            canvas.drawText(
                context.getString(R.string.report_pdf_balance, MoneyFormat.formatRub(balance)),
                40f,
                y,
                bodyPaint,
            )
            y += 18f
            canvas.drawText(
                context.getString(R.string.report_pdf_transactions, data.transactionCount),
                40f,
                y,
                bodyPaint,
            )
            y += 28f
            canvas.drawText(context.getString(R.string.report_pdf_top_expenses), 40f, y, headerPaint)
            y += 22f
            val top = data.expensesByCategory.entries
                .sortedByDescending { it.value }
                .take(12)
            if (top.isEmpty()) {
                canvas.drawText(context.getString(R.string.stats_no_data), 40f, y, mutedPaint)
            } else {
                for (entry in top) {
                    if (y > 780f) break
                    canvas.drawText(
                        "${entry.key} — ${MoneyFormat.formatRub(entry.value)}",
                        40f,
                        y,
                        bodyPaint,
                    )
                    y += 16f
                }
            }
            val generated = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date())
            canvas.drawText(
                context.getString(R.string.report_pdf_generated, generated),
                40f,
                800f,
                mutedPaint,
            )
            doc.finishPage(page)
            doc.writeTo(output)
        } finally {
            doc.close()
        }
    }
}
