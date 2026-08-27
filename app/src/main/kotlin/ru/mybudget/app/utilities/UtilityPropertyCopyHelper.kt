package ru.mybudget.app.utilities

import ru.mybudget.app.data.UtilityDao
import ru.mybudget.app.data.UtilityMeterInfoEntity
import ru.mybudget.app.data.UtilityPropertyEntity
import ru.mybudget.app.data.UtilityTariffEntity
import ru.mybudget.app.data.UtilityTemplateLineEntity
import ru.mybudget.app.setup.ActivePropertyPreferences

object UtilityPropertyCopyHelper {
    suspend fun ensureDefaultProperty(dao: UtilityDao): Int {
        val existing = dao.getAllProperties()
        if (existing.isNotEmpty()) return existing.first().id
        val id = dao.insertProperty(UtilityPropertyEntity(name = "Квартира 1")).toInt()
        if (id <= 0) return ActivePropertyPreferences.DEFAULT_PROPERTY_ID
        return id
    }

    suspend fun createProperty(
        dao: UtilityDao,
        name: String,
        copyFromPropertyId: Int?,
        copyOptions: UtilityPropertyCopyOptions?,
    ): Int {
        val sortOrder = dao.getMaxPropertySortOrder() + 1
        val propertyId = dao.insertProperty(
            UtilityPropertyEntity(name = name, sortOrder = sortOrder),
        ).toInt()
        if (copyFromPropertyId != null && copyOptions?.hasAny() == true) {
            copyPropertyData(dao, copyFromPropertyId, propertyId, copyOptions)
        }
        return propertyId
    }

    suspend fun copyPropertyData(
        dao: UtilityDao,
        fromPropertyId: Int,
        toPropertyId: Int,
        options: UtilityPropertyCopyOptions,
    ) {
        if (!options.hasAny()) return

        if (options.template) {
            clearTemplateForProperty(dao, toPropertyId)
            copyTemplate(dao, fromPropertyId, toPropertyId, copyTariffs = options.tariffs)
        } else if (options.tariffs) {
            copyTariffsByMatchingLines(dao, fromPropertyId, toPropertyId)
        }

        if (options.meters) {
            dao.deleteMeterInfoForProperty(toPropertyId)
            copyMeterCatalog(dao, fromPropertyId, toPropertyId)
        }
    }

    /** @deprecated use [copyPropertyData] with [UtilityPropertyCopyOptions] */
    suspend fun copyTemplateAndMeters(
        dao: UtilityDao,
        fromPropertyId: Int,
        toPropertyId: Int,
        includeMeters: Boolean,
    ) {
        copyPropertyData(
            dao,
            fromPropertyId,
            toPropertyId,
            UtilityPropertyCopyOptions(
                template = true,
                tariffs = true,
                meters = includeMeters,
            ),
        )
    }

    private suspend fun clearTemplateForProperty(dao: UtilityDao, propertyId: Int) {
        for (section in dao.getAllTemplateSections(propertyId)) {
            dao.deleteTariffsForSection(section.id)
            dao.deleteTemplateLinesForSection(section.id)
            dao.deleteTemplateSection(section.id)
        }
    }

    private suspend fun copyTemplate(
        dao: UtilityDao,
        fromPropertyId: Int,
        toPropertyId: Int,
        copyTariffs: Boolean,
    ) {
        for (section in dao.getAllTemplateSections(fromPropertyId)) {
            val newSectionId = dao.insertTemplateSection(
                section.copy(id = 0, propertyId = toPropertyId),
            ).toInt()
            for (line in dao.getTemplateLinesForSection(section.id)) {
                val newLineId = dao.insertTemplateLine(
                    line.copy(id = 0, sectionId = newSectionId),
                ).toInt()
                if (copyTariffs) {
                    copyTariffForLine(dao, line, newLineId)
                }
            }
        }
    }

    private suspend fun copyTariffsByMatchingLines(
        dao: UtilityDao,
        fromPropertyId: Int,
        toPropertyId: Int,
    ) {
        val targetLines = lineKeyMap(dao, toPropertyId)
        for (section in dao.getAllTemplateSections(fromPropertyId)) {
            for (line in dao.getTemplateLinesForSection(section.id)) {
                val key = lineKey(section.name, line)
                val targetLineId = targetLines[key] ?: continue
                copyTariffForLine(dao, line, targetLineId)
            }
        }
    }

    private suspend fun copyTariffForLine(
        dao: UtilityDao,
        sourceLine: UtilityTemplateLineEntity,
        targetLineId: Int,
    ) {
        dao.getTariffForLine(sourceLine.id)?.let { tariff ->
            dao.upsertTariff(
                UtilityTariffEntity(
                    templateLineId = targetLineId,
                    tariff = tariff.tariff,
                    updatedAt = System.currentTimeMillis(),
                ),
            )
        }
    }

    private suspend fun lineKeyMap(dao: UtilityDao, propertyId: Int): Map<String, Int> {
        val map = mutableMapOf<String, Int>()
        for (section in dao.getAllTemplateSections(propertyId)) {
            for (line in dao.getTemplateLinesForSection(section.id)) {
                map[lineKey(section.name, line)] = line.id
            }
        }
        return map
    }

    private fun lineKey(sectionName: String, line: UtilityTemplateLineEntity): String {
        return "$sectionName|${line.groupLabel}|${line.name}|${line.lineMode}"
    }

    private suspend fun copyMeterCatalog(dao: UtilityDao, fromPropertyId: Int, toPropertyId: Int) {
        for (info in dao.getAllMeterInfo(fromPropertyId)) {
            dao.insertMeterInfo(
                UtilityMeterInfoEntity(
                    propertyId = toPropertyId,
                    groupName = info.groupName,
                    meterName = info.meterName,
                    verificationDateLabel = info.verificationDateLabel,
                    verificationEpochDay = info.verificationEpochDay,
                    sortOrder = info.sortOrder,
                ),
            )
        }
    }

    suspend fun deleteProperty(dao: UtilityDao, propertyId: Int): Boolean {
        if (dao.getPropertyCount() <= 1) return false
        for (bill in dao.getAllBills(propertyId)) {
            dao.deleteLineItemsForBill(bill.id)
            dao.deleteSectionsForBill(bill.id)
            dao.deleteBill(bill.id)
        }
        clearTemplateForProperty(dao, propertyId)
        dao.deleteMeterReadingsForProperty(propertyId)
        dao.deleteMeterInfoForProperty(propertyId)
        dao.deleteProperty(propertyId)
        return true
    }
}
