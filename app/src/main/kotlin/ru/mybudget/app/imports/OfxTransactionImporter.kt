package ru.mybudget.app.imports

import ru.mybudget.app.MoneyFormat
import java.text.SimpleDateFormat
import java.util.Locale

object OfxTransactionImporter {
    private val transactionBlockRegex = Regex(
        "<STMTTRN>(.*?)</STMTTRN>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val tagRegex = Regex("<([A-Za-z0-9.]+)>([^<\\r\\n]*)")

    fun isOfx(text: String): Boolean {
        val upper = text.uppercase(Locale.getDefault())
        return upper.contains("OFXHEADER") || upper.contains("<STMTTRN")
    }

    fun parse(text: String): CsvTransactionImporter.ParseResult {
        val rows = mutableListOf<CsvTransactionImporter.ParsedRow>()
        val errors = mutableListOf<String>()
        var skipped = 0
        val format = SimpleDateFormat("yyyyMMdd", Locale.US)
        format.isLenient = false
        transactionBlockRegex.findAll(text).forEachIndexed { index, match ->
            val tags = tagRegex.findAll(match.groupValues[1])
                .associate { it.groupValues[1].uppercase(Locale.getDefault()) to it.groupValues[2].trim() }
            val dateRaw = tags["DTPOSTED"].orEmpty()
            val amountRaw = tags["TRNAMT"].orEmpty()
            val date = parseDate(dateRaw, format)
            val amount = parseAmount(amountRaw)
            if (date == null || amount == null || amount == 0.0) {
                skipped++
                if (errors.size < 5) errors += "Транзакция ${index + 1}: не распознана"
            } else {
                val name = tags["NAME"].orEmpty()
                val memo = tags["MEMO"].orEmpty()
                val description = when {
                    name.isNotBlank() && memo.isNotBlank() && !memo.equals(name, ignoreCase = true) -> "$name · $memo"
                    name.isNotBlank() -> name
                    else -> memo
                }
                val trnType = tags["TRNTYPE"].orEmpty().uppercase(Locale.getDefault())
                val type = when {
                    trnType.startsWith("CREDIT") || trnType == "DEP" || trnType == "XFER" -> "income"
                    else -> "expense"
                }
                rows += CsvTransactionImporter.ParsedRow(
                    dateMillis = date,
                    categoryName = "",
                    type = type,
                    amount = kotlin.math.abs(amount),
                    description = description,
                )
            }
        }
        return CsvTransactionImporter.ParseResult(rows, skipped, errors)
    }

    private fun parseDate(raw: String, format: SimpleDateFormat): Long? {
        val digits = raw.takeWhile { it.isDigit() }
        if (digits.length < 8) return null
        return runCatching { format.parse(digits.take(8))?.time }.getOrNull()
    }

    private fun parseAmount(raw: String): Double? {
        val normalized = raw.replace(" ", "").replace('\u00A0', ' ')
        return MoneyFormat.parse(normalized) ?: normalized.replace(',', '.').toDoubleOrNull()
    }
}
