package ru.mybudget.app.utilities

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

object MeterDateParser {
    private val displayFmt = DateTimeFormatter.ofPattern("dd.MM.yyyy")
    private val isoFmt = DateTimeFormatter.ISO_LOCAL_DATE
    private val dotted = DateTimeFormatter.ofPattern("d.M.yyyy")

    fun formatEpochDay(epochDay: Long): String =
        LocalDate.ofEpochDay(epochDay).format(displayFmt)

    fun formatPeriodLabelForDisplay(label: String): String {
        val parsed = parseToDate(label) ?: return label
        return parsed.format(displayFmt)
    }

    fun parseToDate(raw: String): LocalDate? {
        val s = raw.trim()
        if (s.isEmpty()) return null
        listOf(displayFmt, dotted, isoFmt).forEach { fmt ->
            try {
                return LocalDate.parse(s, fmt)
            } catch (_: DateTimeParseException) {
            }
        }
        return null
    }

    fun parseVerificationDateInput(raw: String): Pair<String, Long>? {
        val date = parseToDate(raw) ?: return null
        return date.format(displayFmt) to date.toEpochDay()
    }

    fun looksLikeVerificationDate(s: String): Boolean {
        if (s.contains("счетчик", ignoreCase = true) || s.contains("счётчик", ignoreCase = true)) return false
        return s.any { it.isDigit() }
    }
}
