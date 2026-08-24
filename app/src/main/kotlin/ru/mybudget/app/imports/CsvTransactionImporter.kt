package ru.mybudget.app.imports

import ru.mybudget.app.MoneyFormat
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.abs

object CsvTransactionImporter {
    data class ParsedRow(
        val dateMillis: Long,
        val categoryName: String,
        val type: String,
        val amount: Double,
        val description: String,
    )

    data class ParseResult(
        val rows: List<ParsedRow>,
        val skipped: Int,
        val errors: List<String>,
    )

    fun parse(text: String): ParseResult {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) {
            return ParseResult(emptyList(), 0, listOf("Пустой файл"))
        }
        val startIndex = if (
            lines.first().contains("date", ignoreCase = true) &&
            lines.first().contains("amount", ignoreCase = true)
        ) {
            1
        } else {
            0
        }
        val rows = mutableListOf<ParsedRow>()
        val errors = mutableListOf<String>()
        var skipped = 0
        for (i in startIndex until lines.size) {
            val parsed = parseLine(lines[i])
            if (parsed != null) {
                rows += parsed
            } else {
                skipped++
                if (errors.size < 5) errors += "Строка ${i + 1}: не распознана"
            }
        }
        return ParseResult(rows, skipped, errors)
    }

    fun resolveCategoryId(categoryName: String, labels: Map<Int, String>): Int? {
        if (categoryName.isBlank()) return null
        val normalized = categoryName.trim().lowercase(Locale.getDefault())
        labels.entries.firstOrNull { it.value.lowercase(Locale.getDefault()) == normalized }?.let { return it.key }
        return labels.entries.firstOrNull { entry ->
            val label = entry.value.lowercase(Locale.getDefault())
            label.endsWith(normalized) || normalized.endsWith(label)
        }?.key
    }

    private fun parseLine(line: String): ParsedRow? {
        val delimiter = when {
            line.count { it == ';' } >= 2 -> ';'
            line.count { it == '\t' } >= 2 -> '\t'
            else -> ','
        }
        val parts = splitCsv(line, delimiter)
        if (parts.size < 3) return null

        if (parts.size >= 5 && looksLikeIsoDate(parts[0])) {
            val date = parseIsoDate(parts[0]) ?: return null
            if (parts.size >= 6 && looksLikeType(parts[3]) && parseAmount(parts[4]) != null) {
                val amount = parseAmount(parts[4]) ?: return null
                val desc = parts.drop(5).joinToString(delimiter.toString()).trim()
                return ParsedRow(
                    dateMillis = date,
                    categoryName = parts[2].trim(),
                    type = normalizeType(parts[3].trim()),
                    amount = amount,
                    description = desc,
                )
            }
            val amount = parseAmount(parts[3]) ?: return null
            val desc = parts.drop(4).joinToString(delimiter.toString()).trim()
            return ParsedRow(
                dateMillis = date,
                categoryName = parts[1].trim(),
                type = normalizeType(parts[2].trim()),
                amount = amount,
                description = desc,
            )
        }

        if (!looksLikeRuDate(parts[0])) return null
        val date = parseRuDate(parts[0]) ?: return null
        val signed = parseAmount(parts[1]) ?: return null
        val desc = parts.drop(2).joinToString(delimiter.toString()).trim()
        return ParsedRow(
            dateMillis = date,
            categoryName = "",
            type = if (signed < 0.0) "expense" else "income",
            amount = abs(signed),
            description = desc,
        )
    }

    private fun parseAmount(raw: String): Double? {
        return MoneyFormat.parse(raw) ?: raw.replace(" ", "").toDoubleOrNull()
    }

    private fun normalizeType(type: String): String {
        val lower = type.lowercase(Locale.getDefault())
        return if (lower.startsWith("inc") || lower == "доход") "income" else "expense"
    }

    private fun looksLikeType(value: String): Boolean {
        val lower = value.trim().lowercase(Locale.getDefault())
        return lower.startsWith("inc") ||
            lower == "доход" ||
            lower.startsWith("exp") ||
            lower == "expense" ||
            lower == "расход"
    }

    private fun looksLikeIsoDate(s: String): Boolean = Regex("""^\d{4}-\d{2}-\d{2}""").containsMatchIn(s)

    private fun looksLikeRuDate(s: String): Boolean = Regex("""^\d{1,2}\.\d{1,2}\.\d{2,4}$""").matches(s)

    private fun parseIsoDate(s: String): Long? {
        val patterns = listOf("yyyy-MM-dd HH:mm", "yyyy-MM-dd")
        for (pattern in patterns) {
            val parsed = runCatching { SimpleDateFormat(pattern, Locale.getDefault()).parse(s) }.getOrNull()
            if (parsed != null) return parsed.time
        }
        return null
    }

    private fun parseRuDate(s: String): Long? {
        val patterns = listOf("dd.MM.yyyy", "dd.MM.yy")
        for (pattern in patterns) {
            val parsed = runCatching { SimpleDateFormat(pattern, Locale.getDefault()).parse(s) }.getOrNull()
            if (parsed != null) return parsed.time
        }
        return null
    }

    private fun splitCsv(line: String, delimiter: Char): List<String> {
        val result = mutableListOf<String>()
        val sb = StringBuilder()
        var inQuotes = false
        for (ch in line) {
            when {
                ch == '"' -> inQuotes = !inQuotes
                ch == delimiter && !inQuotes -> {
                    result += sb.toString().trim()
                    sb.clear()
                }
                else -> sb.append(ch)
            }
        }
        result += sb.toString().trim()
        return result
    }
}
