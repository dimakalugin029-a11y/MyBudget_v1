package ru.mybudget.app.utilities

import ru.mybudget.app.data.UtilityBillEntity

data class UtilityBillSummary(
    val bill: UtilityBillEntity,
    val grandTotal: Double,
)

data class EnrichedUtilityBillSummary(
    val summary: UtilityBillSummary,
    val checklist: UtilityMonthChecklistHelper.Checklist,
    val anomalyPercent: Int?,
    val forecastPercent: Int?,
)
