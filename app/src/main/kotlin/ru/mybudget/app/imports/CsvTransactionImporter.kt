package ru.mybudget.app.imports

import ru.mybudget.app.MoneyFormat
import ru.mybudget.app.setup.ImportCategoryMappingPreferences
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

    private data class BankColumnMap(
        val dateCol: Int,
        val amountCol: Int,
        val descCol: Int,
        val delimiter: Char,
        val expenseCol: Int = -1,
        val incomeCol: Int = -1,
        val categoryCol: Int = -1,
    ) {
        val usesSplitAmounts: Boolean get() = expenseCol >= 0 && incomeCol >= 0
    }

    fun parse(text: String): ParseResult {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) {
            return ParseResult(emptyList(), 0, listOf("Пустой файл"))
        }

        val bankMap = parseBankHeader(lines.first())
        if (bankMap != null) {
            return parseBankLines(lines.drop(1), bankMap)
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

    fun resolveCategoryId(
        categoryName: String,
        labels: Map<Int, String>,
        description: String = "",
        rules: List<ImportCategoryMappingPreferences.Rule> = emptyList(),
    ): Int? {
        if (description.isNotBlank()) {
            ImportCategoryMappingPreferences.resolveCategory(description, rules)?.let { return it }
        }
        if (categoryName.isBlank()) return null
        val normalized = categoryName.trim().lowercase(Locale.getDefault())
        labels.entries.firstOrNull { it.value.lowercase(Locale.getDefault()) == normalized }?.let { return it.key }
        return labels.entries.firstOrNull { entry ->
            val label = entry.value.lowercase(Locale.getDefault())
            label.endsWith(normalized) || normalized.endsWith(label)
        }?.key
    }

    private fun parseBankLines(lines: List<String>, map: BankColumnMap): ParseResult {
        val rows = mutableListOf<ParsedRow>()
        val errors = mutableListOf<String>()
        var skipped = 0
        lines.forEachIndexed { index, line ->
            val parts = splitCsv(line, map.delimiter)
            val parsed = parseBankRow(parts, map)
            if (parsed != null) {
                rows += parsed
            } else {
                skipped++
                if (errors.size < 5) errors += "Строка ${index + 2}: не распознана"
            }
        }
        return ParseResult(rows, skipped, errors)
    }

    private fun parseBankHeader(headerLine: String): BankColumnMap? {
        val lower = headerLine.lowercase(Locale.getDefault())
        if (!lower.contains("дата") && !lower.contains("date")) return null

        val delimiter = detectDelimiter(headerLine)
        val parts = splitCsv(headerLine, delimiter).map { it.lowercase(Locale.getDefault()).trim() }

        val dateCol = parts.indexOfFirst { col ->
            col.contains("дата") && (col.contains("операц") || col.contains("транзак") || col == "date")
        }.takeIf { it >= 0 } ?: parts.indexOfFirst { it.contains("дата") || it == "date" }
        if (dateCol < 0) return null

        val expenseCol = parts.indexOfFirst { col ->
            col.contains("расход") || col.contains("списан") || col.contains("debit")
        }
        val incomeCol = parts.indexOfFirst { col ->
            col.contains("приход") || col.contains("зачисл") || col.contains("credit")
        }
        val hasSplit = expenseCol >= 0 && incomeCol >= 0
        val hasAmountColumn = lower.contains("сумма") || lower.contains("amount")
        if (!hasAmountColumn && !hasSplit) return null

        val amountCol = parts.indexOfFirst { col ->
            col.contains("сумма") && (col.contains("операц") || col.contains("валют") || col.contains("счёта") || col.contains("счета"))
        }.takeIf { it >= 0 } ?: parts.indexOfFirst { it.contains("сумма") || it == "amount" }
        if (amountCol < 0 && !hasSplit) return null

        val descCol = parts.indexOfFirst { col ->
            col.contains("описан") || col.contains("назначен") || col.contains("merchant") || col == "description"
        }
        if (descCol < 0) return null

        val categoryCol = parts.indexOfFirst { col ->
            col.contains("категор") || col.contains("category")
        }

        return BankColumnMap(
            dateCol = dateCol,
            amountCol = amountCol.coerceAtLeast(expenseCol.coerceAtLeast(0)),
            descCol = descCol,
            delimiter = delimiter,
            expenseCol = expenseCol,
            incomeCol = incomeCol,
            categoryCol = categoryCol,
        )
    }

    private fun parseBankRow(parts: List<String>, map: BankColumnMap): ParsedRow? {
        if (parts.size <= maxOf(map.dateCol, map.descCol)) return null
        val dateRaw = parts[map.dateCol].trim()
        val date = parseRuDate(dateRaw) ?: parseIsoDate(dateRaw) ?: return null
        val descBase = parts.getOrNull(map.descCol)?.trim().orEmpty()
        val category = if (map.categoryCol >= 0) parts.getOrNull(map.categoryCol)?.trim().orEmpty() else ""
        val desc = when {
            descBase.isNotBlank() && category.isNotBlank() -> "$category · $descBase"
            descBase.isNotBlank() -> descBase
            category.isNotBlank() -> category
            else -> ""
        }

        if (map.usesSplitAmounts) {
            val expense = parseAmount(parts.getOrElse(map.expenseCol) { "" }) ?: 0.0
            val income = parseAmount(parts.getOrElse(map.incomeCol) { "" }) ?: 0.0
            return when {
                expense > 0.0 && income <= 0.0 -> ParsedRow(date, "", "expense", expense, desc)
                income > 0.0 && expense <= 0.0 -> ParsedRow(date, "", "income", income, desc)
                else -> null
            }
        }

        if (parts.size <= maxOf(map.dateCol, map.amountCol, map.descCol)) return null
        val signed = parseAmount(parts[map.amountCol]) ?: return null
        if (signed == 0.0) return null
        return ParsedRow(
            dateMillis = date,
            categoryName = "",
            type = if (signed < 0.0) "expense" else "income",
            amount = abs(signed),
            description = desc,
        )
    }

    private fun parseLine(line: String): ParsedRow? {
        val delimiter = detectDelimiter(line)
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

    private fun detectDelimiter(line: String): Char {
        return when {
            line.count { it == ';' } >= 2 -> ';'
            line.count { it == '\t' } >= 2 -> '\t'
            else -> ','
        }
    }

    private fun parseAmount(raw: String): Double? {
        return MoneyFormat.parse(raw) ?: raw.replace(" ", "").replace("\"", "").toDoubleOrNull()
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

    private fun looksLikeRuDate(s: String): Boolean = Regex("""^\d{1,2}\.\d{1,2}\.\d{2,4}$""").matches(s.trim())

    private fun parseIsoDate(s: String): Long? {
        val patterns = listOf("yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm", "yyyy-MM-dd")
        for (pattern in patterns) {
            val parsed = runCatching { SimpleDateFormat(pattern, Locale.getDefault()).parse(s.trim()) }.getOrNull()
            if (parsed != null) return parsed.time
        }
        return null
    }

    private fun parseRuDate(s: String): Long? {
        val value = s.trim().substringBefore(' ')
        val patterns = listOf("dd.MM.yyyy", "dd.MM.yy")
        for (pattern in patterns) {
            val parsed = runCatching { SimpleDateFormat(pattern, Locale.getDefault()).parse(value) }.getOrNull()
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
