package ru.mybudget.app.utilities

import ru.mybudget.app.data.UtilityDao
import ru.mybudget.app.data.UtilityMeterInfoEntity
import ru.mybudget.app.data.UtilityMeterReadingEntity
import java.time.LocalDate

class MeterRepository(private val dao: UtilityDao) {
    suspend fun getMeterCatalogSummaries(): List<MeterCatalogSummary> {
        val infos = dao.getAllMeterInfo()
        val readings = dao.getAllMeterReadings().groupBy { it.groupName to it.meterName }
        val today = LocalDate.now()
        return infos.map { info ->
            val list = readings[info.groupName to info.meterName].orEmpty()
                .sortedWith(compareBy({ periodEpoch(it.periodLabel) ?: Long.MIN_VALUE }, { it.sortOrder }))
            val last = list.lastOrNull()
            val hint = MeterReceiptProgressHelper.compute(list, today)?.let { MeterReceiptProgressHelper.formatHint(it) }
            MeterCatalogSummary(info, list.size, last, hint)
        }
    }

    suspend fun getAllMeterInfos(): List<UtilityMeterInfoEntity> = dao.getAllMeterInfo()

    suspend fun waterTotals(): MeterWaterTotals = waterTotals(getMeterCatalogSummaries())

    suspend fun waterTotals(summaries: List<MeterCatalogSummary>): MeterWaterTotals {
        var hvsSum = 0.0
        var gvsSum = 0.0
        var hvsCount = 0
        var gvsCount = 0
        var hvsCons = 0.0
        var gvsCons = 0.0
        summaries.forEach { summary ->
            val last = summary.lastReading ?: return@forEach
            when (waterKind(summary.info.groupName, summary.info.meterName)) {
                WaterKind.HVS -> {
                    hvsCount++
                    hvsSum += last.readingValue
                    hvsCons += last.consumption ?: 0.0
                }
                WaterKind.GVS -> {
                    gvsCount++
                    gvsSum += last.readingValue
                    gvsCons += last.consumption ?: 0.0
                }
                WaterKind.OTHER -> Unit
            }
        }
        return MeterWaterTotals(hvsSum, gvsSum, hvsCount, gvsCount, hvsCons, gvsCons)
    }

    suspend fun createMeter(
        groupName: String,
        meterName: String,
        verificationDateLabel: String,
        verificationEpochDay: Long?,
    ): Boolean {
        if (dao.getMeterInfoByKey(groupName, meterName) != null) return false
        val order = (dao.getAllMeterInfo().maxOfOrNull { it.sortOrder } ?: -1) + 1
        dao.insertMeterInfo(
            UtilityMeterInfoEntity(
                groupName = groupName,
                meterName = meterName,
                verificationDateLabel = verificationDateLabel,
                verificationEpochDay = verificationEpochDay,
                sortOrder = order,
            ),
        )
        return true
    }

    suspend fun updateMeter(existing: UtilityMeterInfoEntity, updated: UtilityMeterInfoEntity): Boolean {
        val keyChanged = existing.groupName != updated.groupName || existing.meterName != updated.meterName
        if (keyChanged && dao.getMeterInfoByKey(updated.groupName, updated.meterName) != null) {
            return false
        }
        if (keyChanged) {
            dao.getMeterReadingsHistory(existing.groupName, existing.meterName).forEach { reading ->
                dao.updateMeterReading(
                    reading.copy(groupName = updated.groupName, meterName = updated.meterName),
                )
            }
        }
        dao.updateMeterInfo(updated)
        return true
    }

    suspend fun updateVerificationDate(
        info: UtilityMeterInfoEntity,
        label: String,
        epochDay: Long?,
    ) {
        dao.updateMeterInfo(info.copy(verificationDateLabel = label, verificationEpochDay = epochDay))
    }

    suspend fun deleteMeter(info: UtilityMeterInfoEntity) {
        dao.deleteReadingsForMeter(info.groupName, info.meterName)
        dao.deleteMeterInfoById(info.id)
    }

    suspend fun getHistory(groupName: String, meterName: String): List<UtilityMeterReadingEntity> {
        return dao.getMeterReadingsHistory(groupName, meterName)
            .sortedWith(compareByDescending<UtilityMeterReadingEntity> { periodEpoch(it.periodLabel) ?: Long.MIN_VALUE }
                .thenByDescending { it.sortOrder })
    }

    suspend fun addMeterReading(
        groupName: String,
        meterName: String,
        periodEpochDay: Long,
        readingValue: Double,
        consumption: Double?,
    ): MeterReadingSaveResult {
        if (readingValue < 0.0) return MeterReadingSaveResult.Invalid
        if (consumption != null && consumption < 0.0) return MeterReadingSaveResult.Invalid
        val periodLabel = LocalDate.ofEpochDay(periodEpochDay).toString()
        val history = dao.getMeterReadingsHistory(groupName, meterName)
            .sortedWith(compareBy({ periodEpoch(it.periodLabel) ?: Long.MIN_VALUE }, { it.sortOrder }))
        if (history.any { it.periodLabel == periodLabel || periodEpoch(it.periodLabel) == periodEpochDay }) {
            return MeterReadingSaveResult.DuplicateDate
        }
        val earlier = history.lastOrNull { (periodEpoch(it.periodLabel) ?: Long.MIN_VALUE) < periodEpochDay }
        val later = history.firstOrNull { (periodEpoch(it.periodLabel) ?: Long.MAX_VALUE) > periodEpochDay }
        if (earlier != null && readingValue + 1e-6 < earlier.readingValue) return MeterReadingSaveResult.InconsistentPast
        if (later != null && readingValue - 1e-6 > later.readingValue) return MeterReadingSaveResult.InconsistentFuture
        val computedConsumption = consumption ?: earlier?.let { (readingValue - it.readingValue).coerceAtLeast(0.0) }
        val sort = (dao.getMaxReadingSortOrder(groupName, meterName) + 1)
        dao.insertMeterReading(
            UtilityMeterReadingEntity(
                groupName = groupName,
                meterName = meterName,
                periodLabel = periodLabel,
                readingValue = readingValue,
                consumption = computedConsumption,
                sortOrder = sort,
            ),
        )
        if (dao.getMeterInfoByKey(groupName, meterName) == null) {
            createMeter(groupName, meterName, "", null)
        }
        return MeterReadingSaveResult.Saved
    }

    suspend fun addMeterReadingsBatch(
        periodEpochDay: Long,
        entries: List<MeterBatchEntry>,
    ): MeterBatchSaveResult {
        var saved = 0
        val failures = mutableListOf<MeterBatchSaveFailure>()
        entries.forEach { entry ->
            when (
                val result = addMeterReading(
                    entry.groupName,
                    entry.meterName,
                    periodEpochDay,
                    entry.readingValue,
                    null,
                )
            ) {
                MeterReadingSaveResult.Saved -> saved++
                else -> failures += MeterBatchSaveFailure(entry.meterName, result)
            }
        }
        return MeterBatchSaveResult(saved, failures)
    }

    suspend fun deleteMeterReading(id: Int) {
        dao.deleteMeterReadingById(id)
    }

    suspend fun compareBills(billIdA: Int, billIdB: Int): UtilityMonthCompare {
        val linesA = linesByKey(billIdA)
        val linesB = linesByKey(billIdB)
        val keys = (linesA.keys + linesB.keys).sorted()
        val rows = keys.map { key ->
            UtilityMonthCompareRow(
                lineLabel = key,
                amountA = linesA[key] ?: 0.0,
                amountB = linesB[key] ?: 0.0,
            )
        }
        return UtilityMonthCompare(
            totalA = rows.sumOf { it.amountA },
            totalB = rows.sumOf { it.amountB },
            rows = rows,
        )
    }

    private suspend fun linesByKey(billId: Int): Map<String, Double> {
        val sections = dao.getSectionsForBill(billId)
        val sums = linkedMapOf<String, Double>()
        sections.forEach { section ->
            dao.getLineItemsForSection(section.id).forEach { line ->
                val label = if (line.groupLabel.isBlank()) line.name else "${line.groupLabel} · ${line.name}"
                sums[label] = (sums[label] ?: 0.0) + line.amount
            }
        }
        return sums
    }

    private fun periodEpoch(label: String): Long? = MeterDateParser.parseToDate(label)?.toEpochDay()

    private fun waterKind(group: String, name: String): WaterKind {
        val text = "$group $name".lowercase()
        return when {
            text.contains("гвс") || text.contains("горяч") -> WaterKind.GVS
            text.contains("хвс") || text.contains("холодн") -> WaterKind.HVS
            else -> WaterKind.OTHER
        }
    }

    private enum class WaterKind { HVS, GVS, OTHER }

    companion object {
        fun messageFor(result: MeterReadingSaveResult): Int? = when (result) {
            MeterReadingSaveResult.Saved -> ru.mybudget.app.R.string.meter_reading_saved
            MeterReadingSaveResult.DuplicateDate -> ru.mybudget.app.R.string.meter_reading_duplicate_date
            MeterReadingSaveResult.InconsistentPast -> ru.mybudget.app.R.string.meter_reading_inconsistent_past
            MeterReadingSaveResult.InconsistentFuture -> ru.mybudget.app.R.string.meter_reading_inconsistent_future
            MeterReadingSaveResult.Invalid -> ru.mybudget.app.R.string.meter_reading_value_invalid
        }
    }
}
