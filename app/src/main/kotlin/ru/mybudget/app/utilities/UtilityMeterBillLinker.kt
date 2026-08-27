package ru.mybudget.app.utilities

import ru.mybudget.app.MoneyFormat
import ru.mybudget.app.data.UtilityDao
import ru.mybudget.app.data.UtilityLineItemEntity
import java.time.LocalDate
import java.util.Locale
import kotlin.math.max

object UtilityMeterBillLinker {
    data class ApplyResult(
        val updatedLines: Int,
        val skippedLines: Int,
        val details: List<String>,
        val changes: List<LineChange> = emptyList(),
    )

    data class LineChange(
        val name: String,
        val oldQuantity: Double?,
        val newQuantity: Double,
        val oldAmount: Double,
        val newAmount: Double,
    )

    private data class MeterConsumption(
        val groupName: String,
        val meterName: String,
        val consumption: Double,
        val sortKey: Long,
    )

    suspend fun applyMeterReadingsToBill(dao: UtilityDao, billId: Int): ApplyResult {
        val bill = dao.getBillById(billId)
            ?: return ApplyResult(0, 0, listOf("Счёт не найден"))
        val consumptions = loadConsumptionsForMonth(dao, bill.propertyId, bill.year, bill.month)
        if (consumptions.isEmpty()) {
            return ApplyResult(0, 0, listOf("Нет показаний за этот месяц"))
        }
        val lines = dao.getLineItemsForBill(billId)
        var skipped = 0
        var updated = 0
        val details = mutableListOf<String>()
        val changes = mutableListOf<LineChange>()
        for (line in lines) {
            val qty = line.quantity
            if (qty != null && line.tariff != null && line.amount > 0.0 && qty > 0.0) {
                skipped++
                continue
            }
            val consumption = findConsumptionForLine(line, consumptions)
            if (consumption == null) {
                skipped++
                continue
            }
            val tariff = line.tariff
            val amount = if (tariff != null) {
                UtilityUserTemplate.computedAmount(consumption, tariff) ?: line.amount
            } else {
                line.amount
            }
            val oldQuantity = line.quantity
            val oldAmount = line.amount
            val roundedQty = MoneyFormat.roundQuantity(consumption)
            dao.updateLineItem(
                line.copy(
                    quantity = roundedQty,
                    amount = amount,
                ),
            )
            changes += LineChange(line.name, oldQuantity, roundedQty, oldAmount, amount)
            details += "${line.name}: $consumption"
            updated++
        }
        return ApplyResult(updated, skipped, details, changes)
    }

    private suspend fun loadConsumptionsForMonth(
        dao: UtilityDao,
        propertyId: Int,
        year: Int,
        month: Int,
    ): List<MeterConsumption> {
        val readings = dao.getAllMeterReadings(propertyId).mapNotNull { reading ->
            val epoch = UtilityExcelParser.parsePeriodToEpochDay(reading.periodLabel) ?: return@mapNotNull null
            val date = LocalDate.ofEpochDay(epoch)
            if (date.year != year || date.monthValue != month) return@mapNotNull null
            val consumption = reading.consumption ?: return@mapNotNull null
            if (consumption < 0.0) return@mapNotNull null
            MeterConsumption(
                groupName = reading.groupName,
                meterName = reading.meterName,
                consumption = consumption,
                sortKey = UtilityExcelParser.readingSortKey(reading.periodLabel, reading.sortOrder),
            )
        }
        return readings
            .groupBy { "${it.groupName}\u0001${it.meterName}" }
            .mapNotNull { (_, group) -> group.maxByOrNull { it.sortKey } }
    }

    private fun findConsumptionForLine(
        line: UtilityLineItemEntity,
        consumptions: List<MeterConsumption>,
    ): Double? {
        val lineKey = normalize("${line.name} ${line.groupLabel}")
        var bestScore = 0
        var bestConsumption: Double? = null
        for (mc in consumptions) {
            val score = matchScore(lineKey, normalize("${mc.meterName} ${mc.groupName}"))
            if (score > bestScore) {
                bestScore = score
                bestConsumption = mc.consumption
            }
        }
        return if (bestScore > 0) bestConsumption else null
    }

    private fun matchScore(lineKey: String, meterKey: String): Int {
        if (lineKey.isBlank() || meterKey.isBlank()) return 0
        if (lineKey.contains(meterKey) || meterKey.contains(lineKey)) return 100
        val rules = listOf(
            listOf("холод", "хвс", "cold") to listOf("холод", "хвс", "cold"),
            listOf("горяч", "гвс", "hot") to listOf("горяч", "гвс", "hot"),
            listOf("элект", "свет", "ee") to listOf("элект", "свет", "день", "ночь", "ee"),
            listOf("водоот", "канал") to listOf("водоот", "канал"),
            listOf("газ") to listOf("газ"),
        )
        var score = 0
        for ((lineTokens, meterTokens) in rules) {
            val lineHit = lineTokens.any { lineKey.contains(it) }
            val meterHit = meterTokens.any { meterKey.contains(it) }
            if (lineHit && meterHit) score = max(score, 80)
        }
        val lineWords = lineKey.split(' ').filter { it.length > 2 }
        val meterWords = meterKey.split(' ').filter { it.length > 2 }
        val common = lineWords.count { word ->
            meterWords.any { it.contains(word) || word.contains(it) }
        }
        return max(score, common * 15)
    }

    private fun normalize(s: String): String {
        return s.lowercase(Locale.getDefault())
            .replace(Regex("[^a-zа-яё0-9\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}
