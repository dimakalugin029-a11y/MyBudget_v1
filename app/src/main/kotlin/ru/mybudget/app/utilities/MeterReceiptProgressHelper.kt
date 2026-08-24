package ru.mybudget.app.utilities

import ru.mybudget.app.data.UtilityMeterReadingEntity
import java.time.LocalDate
import kotlin.math.max
import kotlin.math.roundToInt

object MeterReceiptProgressHelper {
    data class Progress(
        val daysSinceLast: Int?,
        val avgIntervalDays: Int?,
        val daysUntilSuggested: Int?,
    )

    fun compute(
        readings: List<UtilityMeterReadingEntity>,
        today: LocalDate = LocalDate.now(),
    ): Progress? {
        if (readings.isEmpty()) return null
        val epochs = readings.mapNotNull { UtilityExcelParser.parsePeriodToEpochDay(it.periodLabel) }.sorted()
        if (epochs.isEmpty()) return null
        val daysSinceLast = max(0, (today.toEpochDay() - epochs.last()).toInt())
        val gaps = epochs.zipWithNext { a, b -> (b - a).toInt() }.filter { it > 0 }
        val avg = if (gaps.isEmpty()) null else max(1, gaps.average().roundToInt())
        val until = avg?.let { max(0, it - daysSinceLast) }
        return Progress(daysSinceLast, avg, until)
    }

    fun formatHint(progress: Progress): String? {
        val daysUntil = progress.daysUntilSuggested ?: return null
        return when {
            daysUntil <= 0 -> "Показания: пора передать"
            daysUntil <= 7 -> "Показания: ~$daysUntil дн."
            progress.avgIntervalDays == null -> null
            else -> "След. показания ~ через $daysUntil дн. (ср. ${progress.avgIntervalDays} дн.)"
        }
    }
}
