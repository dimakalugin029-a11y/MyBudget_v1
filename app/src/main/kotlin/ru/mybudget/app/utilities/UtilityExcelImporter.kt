package ru.mybudget.app.utilities

import ru.mybudget.app.data.UtilityBillEntity
import ru.mybudget.app.data.UtilityDao
import ru.mybudget.app.data.UtilityLineItemEntity
import ru.mybudget.app.data.UtilityMeterInfoEntity
import ru.mybudget.app.data.UtilityMeterReadingEntity
import ru.mybudget.app.data.UtilitySectionEntity
import java.io.InputStream

class UtilityExcelImporter(
    private val utilityDao: UtilityDao,
    private val propertyId: Int,
) {

    data class ImportResult(
        val monthsImported: Int,
        val meterRowsImported: Int,
        val verificationRowsImported: Int = 0,
        val catalogEntries: Int = 0,
    ) {
        val isEmpty: Boolean
            get() = monthsImported == 0 && meterRowsImported == 0 && catalogEntries == 0
    }

    data class MetersImportResult(
        val catalogEntries: Int,
        val readingsImported: Int,
        val readingsSkipped: Int,
        val verificationsUpdated: Int,
    )

    suspend fun importFromStream(input: InputStream, replaceExisting: Boolean): ImportResult {
        val parsed = UtilityExcelParser.parse(input)
        if (replaceExisting && parsed.bills.isNotEmpty()) {
            for (bill in utilityDao.getAllBills(propertyId)) {
                utilityDao.deleteLineItemsForBill(bill.id)
                utilityDao.deleteSectionsForBill(bill.id)
                utilityDao.deleteBill(bill.id)
            }
        }
        var months = 0
        parsed.bills.forEach { bill ->
            val existing = utilityDao.getBillByPeriod(propertyId, bill.year, bill.month)
            if (existing != null && !replaceExisting) return@forEach
            if (existing != null) {
                utilityDao.deleteLineItemsForBill(existing.id)
                utilityDao.deleteSectionsForBill(existing.id)
                utilityDao.deleteBill(existing.id)
            }
            val billId = utilityDao.insertBill(
                UtilityBillEntity(
                    propertyId = propertyId,
                    year = bill.year,
                    month = bill.month,
                    apartmentArea = bill.apartmentArea,
                ),
            ).toInt()
            bill.sections.forEachIndexed { sectionOrder, section ->
                val sectionId = utilityDao.insertSection(
                    UtilitySectionEntity(billId = billId, name = section.name, sortOrder = sectionOrder),
                ).toInt()
                section.lines.forEachIndexed { lineOrder, line ->
                    utilityDao.insertLineItem(
                        UtilityLineItemEntity(
                            sectionId = sectionId,
                            groupLabel = line.groupLabel,
                            name = line.name,
                            quantity = line.quantity,
                            tariff = line.tariff,
                            amount = line.amount,
                            sortOrder = lineOrder,
                        ),
                    )
                }
            }
            months++
        }
        val meters = importMetersInternal(
            catalog = parsed.meterCatalog,
            readings = parsed.meterReadings,
            verifications = parsed.meterVerifications,
            replaceReadings = false,
        )
        return ImportResult(
            monthsImported = months,
            meterRowsImported = meters.readingsImported,
            verificationRowsImported = meters.verificationsUpdated,
            catalogEntries = meters.catalogEntries,
        )
    }

    suspend fun importMetersFromStream(input: InputStream, replaceReadings: Boolean): MetersImportResult {
        val parsed = UtilityExcelParser.parseMetersOnly(input)
        return importMetersInternal(parsed.catalog, parsed.readings, parsed.verifications, replaceReadings)
    }

    private suspend fun importMetersInternal(
        catalog: List<UtilityExcelParser.ParsedMeterCatalogEntry>,
        readings: List<UtilityExcelParser.ParsedMeterReading>,
        verifications: List<UtilityExcelParser.ParsedMeterVerification>,
        replaceReadings: Boolean,
    ): MetersImportResult {
        var catalogCount = 0
        catalog.forEach { entry ->
            if (MeterExcelFormat.isExampleRow(entry.groupName, entry.meterName)) return@forEach
            upsertMeterInfo(entry.groupName, entry.meterName, entry.verificationLabel, entry.verificationEpochDay)
            catalogCount++
        }
        if (replaceReadings && readings.isNotEmpty()) {
            utilityDao.deleteMeterReadingsForProperty(propertyId)
        }
        val existingKeys = if (replaceReadings) {
            mutableSetOf<String>()
        } else {
            utilityDao.getAllMeterReadings(propertyId).map { readingKey(it) }.toHashSet()
        }
        var imported = 0
        var skipped = 0
        val sortBase = mutableMapOf<Pair<String, String>, Int>()
        readings.forEach { reading ->
            if (MeterExcelFormat.isExampleRow(reading.groupName, reading.meterName)) return@forEach
            upsertMeterInfo(reading.groupName, reading.meterName, "", null)
            val key = readingKey(reading.groupName, reading.meterName, reading.periodLabel)
            if (key in existingKeys) {
                skipped++
                return@forEach
            }
            val pair = reading.groupName to reading.meterName
            val order = (sortBase[pair] ?: utilityDao.getMaxReadingSortOrder(propertyId, reading.groupName, reading.meterName)) + 1
            sortBase[pair] = order
            utilityDao.insertMeterReading(
                UtilityMeterReadingEntity(
                    propertyId = propertyId,
                    groupName = reading.groupName,
                    meterName = reading.meterName,
                    periodLabel = reading.periodLabel,
                    readingValue = reading.readingValue,
                    consumption = reading.consumption,
                    sortOrder = order,
                ),
            )
            existingKeys += key
            imported++
        }
        var verificationsUpdated = 0
        verifications.forEach { ver ->
            val name = UtilityExcelParser.normalizeMeterName(ver.meterName)
            val infos = utilityDao.getAllMeterInfo(propertyId).filter {
                UtilityExcelParser.normalizeMeterName(it.meterName).equals(name, ignoreCase = true)
            }
            infos.forEach { info ->
                if (info.verificationDateLabel != ver.dateLabel || info.verificationEpochDay != ver.epochDay) {
                    utilityDao.updateMeterInfo(
                        info.copy(
                            verificationDateLabel = ver.dateLabel,
                            verificationEpochDay = ver.epochDay,
                        ),
                    )
                    verificationsUpdated++
                }
            }
        }
        return MetersImportResult(catalogCount, imported, skipped, verificationsUpdated)
    }

    private suspend fun upsertMeterInfo(
        groupName: String,
        meterName: String,
        verificationLabel: String,
        verificationEpochDay: Long?,
    ) {
        val name = UtilityExcelParser.normalizeMeterName(meterName)
        val existing = utilityDao.getMeterInfoByKey(propertyId, groupName, name)
        if (existing == null) {
            val order = (utilityDao.getAllMeterInfo(propertyId).maxOfOrNull { it.sortOrder } ?: -1) + 1
            utilityDao.insertMeterInfo(
                UtilityMeterInfoEntity(
                    propertyId = propertyId,
                    groupName = groupName,
                    meterName = name,
                    verificationDateLabel = verificationLabel,
                    verificationEpochDay = verificationEpochDay,
                    sortOrder = order,
                ),
            )
        } else if (verificationLabel.isNotBlank()) {
            utilityDao.updateMeterInfo(
                existing.copy(
                    verificationDateLabel = verificationLabel,
                    verificationEpochDay = verificationEpochDay ?: existing.verificationEpochDay,
                ),
            )
        }
    }

    private fun readingKey(entity: UtilityMeterReadingEntity): String =
        readingKey(entity.groupName, entity.meterName, entity.periodLabel)

    private fun readingKey(group: String, meter: String, period: String): String =
        "${group.trim().lowercase()}|${UtilityExcelParser.normalizeMeterName(meter).lowercase()}|${period.trim()}"
}
