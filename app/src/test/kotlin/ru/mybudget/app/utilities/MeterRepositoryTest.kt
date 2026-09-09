package ru.mybudget.app.utilities

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mybudget.app.data.BillGrandTotal
import ru.mybudget.app.data.BillPhotoCount
import ru.mybudget.app.data.UtilityBillEntity
import ru.mybudget.app.data.UtilityBillPhotoEntity
import ru.mybudget.app.data.UtilityDao
import ru.mybudget.app.data.UtilityLineItemEntity
import ru.mybudget.app.data.UtilityMeterInfoEntity
import ru.mybudget.app.data.UtilityMeterReadingEntity
import ru.mybudget.app.data.UtilityPropertyEntity
import ru.mybudget.app.data.UtilitySectionEntity
import ru.mybudget.app.data.UtilityTariffEntity
import ru.mybudget.app.data.UtilityTemplateLineEntity
import ru.mybudget.app.data.UtilityTemplateSectionEntity
import java.time.LocalDate

class MeterRepositoryTest {

    private fun serialLabel(date: LocalDate): String =
        (date.toEpochDay() - LocalDate.of(1899, 12, 30).toEpochDay()).toString()

    private fun repositoryWith(vararg readings: UtilityMeterReadingEntity): Pair<FakeUtilityDao, MeterRepository> {
        val dao = FakeUtilityDao()
        readings.forEach { runBlocking { dao.insertMeterReading(it) } }
        return dao to MeterRepository(dao, propertyId = 1)
    }

    @Test
    fun addReading_detectsDuplicateDateWithExcelSerialLabel() = runBlocking {
        val day = LocalDate.of(2026, 9, 8)
        val (_, repo) = repositoryWith(
            UtilityMeterReadingEntity(meterName = "ХВС", periodLabel = serialLabel(day), readingValue = 100.0),
        )
        val result = repo.addMeterReading("", "ХВС", day.toEpochDay(), 110.0, null)
        assertEquals(MeterReadingSaveResult.DuplicateDate, result)
    }

    @Test
    fun addReading_allowsValueBelowFutureSerialLabelReading() = runBlocking {
        val (_, repo) = repositoryWith(
            UtilityMeterReadingEntity(
                meterName = "ХВС",
                periodLabel = serialLabel(LocalDate.of(2026, 9, 10)),
                readingValue = 500.0,
            ),
        )
        val result = repo.addMeterReading("", "ХВС", LocalDate.of(2026, 9, 8).toEpochDay(), 400.0, null)
        assertEquals(MeterReadingSaveResult.Saved, result)
    }

    @Test
    fun addReading_rejectsValueBelowEarlierIsoReading() = runBlocking {
        val (_, repo) = repositoryWith(
            UtilityMeterReadingEntity(meterName = "ХВС", periodLabel = "2026-08-31", readingValue = 300.0),
        )
        val result = repo.addMeterReading("", "ХВС", LocalDate.of(2026, 9, 8).toEpochDay(), 250.0, null)
        assertEquals(MeterReadingSaveResult.InconsistentPast, result)
    }

    @Test
    fun addReading_computesConsumptionFromLatestEarlierReading() = runBlocking {
        val (dao, repo) = repositoryWith(
            UtilityMeterReadingEntity(meterName = "ХВС", periodLabel = "2026-08-31", readingValue = 300.0, sortOrder = 0),
            UtilityMeterReadingEntity(
                meterName = "ХВС",
                periodLabel = serialLabel(LocalDate.of(2026, 9, 5)),
                readingValue = 320.0,
                sortOrder = 1,
            ),
        )
        val result = repo.addMeterReading("", "ХВС", LocalDate.of(2026, 9, 8).toEpochDay(), 350.0, null)
        assertEquals(MeterReadingSaveResult.Saved, result)
        val saved = dao.readings.maxByOrNull { it.id }
        assertEquals(30.0, saved?.consumption!!, 1e-9)
    }

    @Test
    fun addReading_supportsRussianPeriodLabel() = runBlocking {
        val (_, repo) = repositoryWith(
            UtilityMeterReadingEntity(meterName = "ХВС", periodLabel = "31 августа 2026", readingValue = 300.0),
        )
        val result = repo.addMeterReading("", "ХВС", LocalDate.of(2026, 9, 8).toEpochDay(), 350.0, null)
        assertEquals(MeterReadingSaveResult.Saved, result)
    }

    @Test
    fun addReading_ignoresUndateableLabelsInConsistencyChecks() = runBlocking {
        val (_, repo) = repositoryWith(
            UtilityMeterReadingEntity(meterName = "ХВС", periodLabel = "август 2026", readingValue = 500.0),
        )
        val result = repo.addMeterReading("", "ХВС", LocalDate.of(2026, 9, 8).toEpochDay(), 400.0, null)
        assertEquals(MeterReadingSaveResult.Saved, result)
    }

    @Test
    fun batchSave_savesMetersWithImportedHistory() = runBlocking {
        val (_, repo) = repositoryWith(
            UtilityMeterReadingEntity(
                groupName = "Кухня",
                meterName = "ХВС",
                periodLabel = serialLabel(LocalDate.of(2026, 9, 10)),
                readingValue = 500.0,
            ),
        )
        val result = repo.addMeterReadingsBatch(
            LocalDate.of(2026, 9, 8).toEpochDay(),
            listOf(MeterBatchEntry("Кухня", "ХВС", 400.0)),
        )
        assertEquals(1, result.saved)
        assertTrue(result.failures.isEmpty())
    }

    private class FakeUtilityDao : UtilityDao {
        val readings = mutableListOf<UtilityMeterReadingEntity>()
        val infos = mutableListOf<UtilityMeterInfoEntity>()
        private var nextId = 1

        private fun readingsFor(propertyId: Int, groupName: String, meterName: String) =
            readings.filter {
                it.propertyId == propertyId && it.meterName == meterName &&
                    (groupName == "" || it.groupName == groupName)
            }

        override suspend fun insertMeterReading(reading: UtilityMeterReadingEntity): Long {
            val withId = reading.copy(id = nextId++)
            readings += withId
            return withId.id.toLong()
        }

        override suspend fun getMeterReadingsHistory(
            propertyId: Int,
            groupName: String,
            meterName: String,
        ): List<UtilityMeterReadingEntity> = readingsFor(propertyId, groupName, meterName).sortedBy { it.sortOrder }

        override suspend fun getMaxReadingSortOrder(propertyId: Int, groupName: String, meterName: String): Int =
            readingsFor(propertyId, groupName, meterName).maxOfOrNull { it.sortOrder } ?: -1

        override suspend fun getAllMeterReadings(propertyId: Int): List<UtilityMeterReadingEntity> =
            readings.filter { it.propertyId == propertyId }.sortedBy { it.sortOrder }

        override suspend fun insertMeterInfo(info: UtilityMeterInfoEntity): Long {
            val index = infos.indexOfFirst {
                it.propertyId == info.propertyId && it.groupName == info.groupName && it.meterName == info.meterName
            }
            val withId = info.copy(id = if (index >= 0) infos[index].id else nextId++)
            if (index >= 0) infos[index] = withId else infos += withId
            return withId.id.toLong()
        }

        override suspend fun getMeterInfoByKey(
            propertyId: Int,
            groupName: String,
            meterName: String,
        ): UtilityMeterInfoEntity? = infos.firstOrNull {
            it.propertyId == propertyId && it.groupName == groupName && it.meterName == meterName
        }

        override suspend fun getAllMeterInfo(propertyId: Int): List<UtilityMeterInfoEntity> =
            infos.filter { it.propertyId == propertyId }.sortedBy { it.sortOrder }

        override suspend fun updateMeterInfo(info: UtilityMeterInfoEntity) {
            val index = infos.indexOfFirst { it.id == info.id }
            if (index >= 0) infos[index] = info
        }

        override suspend fun updateMeterReading(reading: UtilityMeterReadingEntity) {
            val index = readings.indexOfFirst { it.id == reading.id }
            if (index >= 0) readings[index] = reading
        }

        override suspend fun insertProperty(property: UtilityPropertyEntity): Long = TODO()
        override suspend fun updateProperty(property: UtilityPropertyEntity) = TODO()
        override suspend fun getAllProperties(): List<UtilityPropertyEntity> = TODO()
        override suspend fun getPropertyById(id: Int): UtilityPropertyEntity? = TODO()
        override suspend fun getPropertyCount(): Int = TODO()
        override suspend fun getMaxPropertySortOrder(): Int = TODO()
        override suspend fun deleteProperty(id: Int) = TODO()
        override suspend fun insertBill(bill: UtilityBillEntity): Long = TODO()
        override suspend fun insertSection(section: UtilitySectionEntity): Long = TODO()
        override suspend fun insertLineItem(item: UtilityLineItemEntity): Long = TODO()
        override suspend fun insertTemplateSection(section: UtilityTemplateSectionEntity): Long = TODO()
        override suspend fun insertTemplateLine(line: UtilityTemplateLineEntity): Long = TODO()
        override suspend fun upsertTariff(tariff: UtilityTariffEntity): Long = TODO()
        override suspend fun insertBillPhoto(photo: UtilityBillPhotoEntity): Long = TODO()
        override suspend fun updateBill(bill: UtilityBillEntity) = TODO()
        override suspend fun updateLineItem(item: UtilityLineItemEntity) = TODO()
        override suspend fun updateTemplateSection(section: UtilityTemplateSectionEntity) = TODO()
        override suspend fun updateTemplateLine(line: UtilityTemplateLineEntity) = TODO()
        override suspend fun getAllBills(propertyId: Int): List<UtilityBillEntity> = TODO()
        override suspend fun getAllBills(): List<UtilityBillEntity> = TODO()
        override suspend fun getBillById(id: Int): UtilityBillEntity? = TODO()
        override suspend fun getPhotoCountForBill(billId: Int): Int = TODO()
        override suspend fun getPhotosForBill(billId: Int): List<UtilityBillPhotoEntity> = TODO()
        override suspend fun getPhotoById(id: Int): UtilityBillPhotoEntity? = TODO()
        override suspend fun getMaxPhotoSortOrder(billId: Int): Int = TODO()
        override suspend fun getAllBillPhotosForExport(): List<UtilityBillPhotoEntity> = TODO()
        override suspend fun deleteBillPhotoById(id: Int) = TODO()
        override suspend fun deleteAllBillPhotos() = TODO()
        override suspend fun getBillByPeriod(propertyId: Int, year: Int, month: Int): UtilityBillEntity? = TODO()
        override suspend fun countUnpaidBillsForMonth(propertyId: Int, year: Int, month: Int): Int = TODO()
        override suspend fun getBillByPaymentGroupId(groupId: String): UtilityBillEntity? = TODO()
        override suspend fun getSectionsForBill(billId: Int): List<UtilitySectionEntity> = TODO()
        override suspend fun getLineItemsForSection(sectionId: Int): List<UtilityLineItemEntity> = TODO()
        override suspend fun getLineItemsForBill(billId: Int): List<UtilityLineItemEntity> = TODO()
        override suspend fun getBillGrandTotals(): List<BillGrandTotal> = TODO()
        override suspend fun getBillCount(): Int = TODO()
        override suspend fun getAllBillsForExport(): List<UtilityBillEntity> = TODO()
        override suspend fun getAllSectionsForExport(): List<UtilitySectionEntity> = TODO()
        override suspend fun getAllLineItemsForExport(): List<UtilityLineItemEntity> = TODO()
        override suspend fun getAllMeterReadings(): List<UtilityMeterReadingEntity> = TODO()
        override suspend fun getAllMeterReadingsForExport(): List<UtilityMeterReadingEntity> = TODO()
        override suspend fun getAllMeterInfo(): List<UtilityMeterInfoEntity> = TODO()
        override suspend fun getMeterInfoById(id: Int): UtilityMeterInfoEntity? = TODO()
        override suspend fun getAllMeterInfoForExport(): List<UtilityMeterInfoEntity> = TODO()
        override suspend fun getTemplateSectionCount(propertyId: Int): Int = TODO()
        override suspend fun getAllTemplateSections(propertyId: Int): List<UtilityTemplateSectionEntity> = TODO()
        override suspend fun getAllTemplateSections(): List<UtilityTemplateSectionEntity> = TODO()
        override suspend fun getTemplateSectionById(id: Int): UtilityTemplateSectionEntity? = TODO()
        override suspend fun getMaxTemplateSectionSortOrder(propertyId: Int): Int = TODO()
        override suspend fun getTemplateLinesForSection(sectionId: Int): List<UtilityTemplateLineEntity> = TODO()
        override suspend fun getTemplateLineById(id: Int): UtilityTemplateLineEntity? = TODO()
        override suspend fun getAllTemplateLines(): List<UtilityTemplateLineEntity> = TODO()
        override suspend fun getMaxTemplateLineSortOrder(sectionId: Int): Int = TODO()
        override suspend fun getAllTemplateSectionsForExport(): List<UtilityTemplateSectionEntity> = TODO()
        override suspend fun getAllTemplateLinesForExport(): List<UtilityTemplateLineEntity> = TODO()
        override suspend fun getTariffForLine(lineId: Int): UtilityTariffEntity? = TODO()
        override suspend fun getAllTariffs(): List<UtilityTariffEntity> = TODO()
        override suspend fun getAllTariffsForExport(): List<UtilityTariffEntity> = TODO()
        override suspend fun getTemplateTariffLineCount(propertyId: Int): Int = TODO()
        override suspend fun getFilledTariffCount(propertyId: Int): Int = TODO()
        override suspend fun countReadingsWithoutCatalogEntry(propertyId: Int): Int = TODO()
        override suspend fun clearBudgetPayment(billId: Int) = TODO()
        override suspend fun deleteBill(id: Int) = TODO()
        override suspend fun deleteLineItemById(id: Int) = TODO()
        override suspend fun deleteLineItemsForBill(billId: Int) = TODO()
        override suspend fun deleteSectionsForBill(billId: Int) = TODO()
        override suspend fun deleteAllLineItems() = TODO()
        override suspend fun deleteAllSections() = TODO()
        override suspend fun deleteAllBills() = TODO()
        override suspend fun deleteMeterReadingById(id: Int) = TODO()
        override suspend fun deleteReadingsForMeter(propertyId: Int, groupName: String, meterName: String) = TODO()
        override suspend fun deleteAllMeterReadings() = TODO()
        override suspend fun deleteMeterInfoById(id: Int) = TODO()
        override suspend fun deleteAllMeterInfo() = TODO()
        override suspend fun deleteTemplateSection(id: Int) = TODO()
        override suspend fun deleteTemplateLine(id: Int) = TODO()
        override suspend fun deleteTemplateLinesForSection(sectionId: Int) = TODO()
        override suspend fun deleteAllTemplateLines() = TODO()
        override suspend fun deleteAllTemplateSections() = TODO()
        override suspend fun deleteTariffForLine(lineId: Int) = TODO()
        override suspend fun deleteTariffsForSection(sectionId: Int) = TODO()
        override suspend fun deleteBillsForProperty(propertyId: Int) = TODO()
        override suspend fun deleteMeterReadingsForProperty(propertyId: Int) = TODO()
        override suspend fun deleteMeterInfoForProperty(propertyId: Int) = TODO()
        override suspend fun deleteTemplateSectionsForProperty(propertyId: Int) = TODO()
        override suspend fun getAllPropertiesForExport(): List<UtilityPropertyEntity> = TODO()
        override suspend fun deleteAllProperties() = TODO()
        override suspend fun deleteAllTariffs() = TODO()
        override suspend fun getPhotoCountsByBill(): List<BillPhotoCount> = TODO()
    }
}
