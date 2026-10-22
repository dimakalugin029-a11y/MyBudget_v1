package ru.mybudget.app

import ru.mybudget.app.data.PlannedObligationEntity
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

object PaymentTracking {
    fun startMonth(item: PlannedObligationEntity, today: LocalDate): YearMonth {
        val stored: String? = item.attentionStartMonth
        if (!stored.isNullOrBlank()) {
            runCatching { YearMonth.parse(stored) }.getOrNull()?.let { return it }
        }
        // New records use their creation month; existing records receive a boundary in migration/import.
        if (item.createdAt <= 0) return YearMonth.from(today)
        return YearMonth.from(Instant.ofEpochMilli(item.createdAt).atZone(ZoneId.systemDefault()).toLocalDate())
    }

    fun importedStartMonth(value: String?, today: LocalDate = LocalDate.now()): String =
        value?.let { runCatching { YearMonth.parse(it).toString() }.getOrNull() }
            ?: YearMonth.from(today).toString()
}
