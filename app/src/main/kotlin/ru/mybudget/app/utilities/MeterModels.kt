package ru.mybudget.app.utilities

import ru.mybudget.app.data.UtilityMeterInfoEntity
import ru.mybudget.app.data.UtilityMeterReadingEntity

data class MeterCatalogSummary(
    val info: UtilityMeterInfoEntity,
    val readingsCount: Int,
    val lastReading: UtilityMeterReadingEntity?,
    val progressHint: String? = null,
)

data class MeterWaterTotals(
    val hvsReadingSum: Double = 0.0,
    val gvsReadingSum: Double = 0.0,
    val hvsMeterCount: Int = 0,
    val gvsMeterCount: Int = 0,
    val hvsConsumptionSum: Double = 0.0,
    val gvsConsumptionSum: Double = 0.0,
) {
    val hasHvs: Boolean get() = hvsMeterCount > 0
    val hasGvs: Boolean get() = gvsMeterCount > 0
    val hasAny: Boolean get() = hasHvs || hasGvs
}

data class MeterBatchEntry(
    val groupName: String,
    val meterName: String,
    val readingValue: Double,
)

sealed class MeterReadingSaveResult {
    data object Saved : MeterReadingSaveResult()
    data object DuplicateDate : MeterReadingSaveResult()
    data object InconsistentPast : MeterReadingSaveResult()
    data object InconsistentFuture : MeterReadingSaveResult()
    data object Invalid : MeterReadingSaveResult()
}

data class MeterBatchSaveFailure(
    val meterName: String,
    val result: MeterReadingSaveResult,
)

data class MeterBatchSaveResult(
    val saved: Int,
    val failures: List<MeterBatchSaveFailure>,
)

data class UtilityMonthCompareRow(
    val lineLabel: String,
    val amountA: Double,
    val amountB: Double,
) {
    val diff: Double get() = amountB - amountA
}

data class UtilityMonthCompare(
    val totalA: Double,
    val totalB: Double,
    val rows: List<UtilityMonthCompareRow>,
)
