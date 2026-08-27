package ru.mybudget.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface UtilityDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProperty(property: UtilityPropertyEntity): Long

    @Update
    suspend fun updateProperty(property: UtilityPropertyEntity)

    @Query("SELECT * FROM utility_properties ORDER BY sortOrder, id")
    suspend fun getAllProperties(): List<UtilityPropertyEntity>

    @Query("SELECT * FROM utility_properties WHERE id = :id")
    suspend fun getPropertyById(id: Int): UtilityPropertyEntity?

    @Query("SELECT COUNT(*) FROM utility_properties")
    suspend fun getPropertyCount(): Int

    @Query("SELECT COALESCE(MAX(sortOrder), -1) FROM utility_properties")
    suspend fun getMaxPropertySortOrder(): Int

    @Query("DELETE FROM utility_properties WHERE id = :id")
    suspend fun deleteProperty(id: Int)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBill(bill: UtilityBillEntity): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSection(section: UtilitySectionEntity): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertLineItem(item: UtilityLineItemEntity): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertMeterReading(reading: UtilityMeterReadingEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMeterInfo(info: UtilityMeterInfoEntity): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertTemplateSection(section: UtilityTemplateSectionEntity): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertTemplateLine(line: UtilityTemplateLineEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTariff(tariff: UtilityTariffEntity): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertBillPhoto(photo: UtilityBillPhotoEntity): Long

    @Update
    suspend fun updateBill(bill: UtilityBillEntity)

    @Update
    suspend fun updateLineItem(item: UtilityLineItemEntity)

    @Update
    suspend fun updateMeterReading(reading: UtilityMeterReadingEntity)

    @Update
    suspend fun updateMeterInfo(info: UtilityMeterInfoEntity)

    @Update
    suspend fun updateTemplateSection(section: UtilityTemplateSectionEntity)

    @Update
    suspend fun updateTemplateLine(line: UtilityTemplateLineEntity)

    @Query("SELECT * FROM utility_bills WHERE propertyId = :propertyId ORDER BY year DESC, month DESC")
    suspend fun getAllBills(propertyId: Int): List<UtilityBillEntity>

    @Query("SELECT * FROM utility_bills ORDER BY year DESC, month DESC")
    suspend fun getAllBills(): List<UtilityBillEntity>

    @Query("SELECT * FROM utility_bills WHERE id = :id")
    suspend fun getBillById(id: Int): UtilityBillEntity?

    @Query("SELECT COUNT(*) FROM utility_bill_photos WHERE billId = :billId")
    suspend fun getPhotoCountForBill(billId: Int): Int

    @Query("SELECT * FROM utility_bill_photos WHERE billId = :billId ORDER BY sortOrder, createdAt")
    suspend fun getPhotosForBill(billId: Int): List<UtilityBillPhotoEntity>

    @Query("SELECT * FROM utility_bill_photos WHERE id = :id")
    suspend fun getPhotoById(id: Int): UtilityBillPhotoEntity?

    @Query(
        """
        SELECT billId AS billId, COUNT(*) AS count
        FROM utility_bill_photos
        GROUP BY billId
        """,
    )
    suspend fun getPhotoCountsByBill(): List<BillPhotoCount>

    @Query("SELECT COALESCE(MAX(sortOrder), -1) FROM utility_bill_photos WHERE billId = :billId")
    suspend fun getMaxPhotoSortOrder(billId: Int): Int

    @Query("SELECT * FROM utility_bill_photos")
    suspend fun getAllBillPhotosForExport(): List<UtilityBillPhotoEntity>

    @Query("DELETE FROM utility_bill_photos WHERE id = :id")
    suspend fun deleteBillPhotoById(id: Int)

    @Query("DELETE FROM utility_bill_photos")
    suspend fun deleteAllBillPhotos()

    @Query("SELECT * FROM utility_bills WHERE propertyId = :propertyId AND year = :year AND month = :month LIMIT 1")
    suspend fun getBillByPeriod(propertyId: Int, year: Int, month: Int): UtilityBillEntity?

    @Query("SELECT COUNT(*) FROM utility_bills WHERE propertyId = :propertyId AND year = :year AND month = :month AND budgetPaidAt IS NULL")
    suspend fun countUnpaidBillsForMonth(propertyId: Int, year: Int, month: Int): Int

    @Query("SELECT * FROM utility_bills WHERE budgetPaymentGroupId = :groupId LIMIT 1")
    suspend fun getBillByPaymentGroupId(groupId: String): UtilityBillEntity?

    @Query("SELECT * FROM utility_sections WHERE billId = :billId ORDER BY sortOrder")
    suspend fun getSectionsForBill(billId: Int): List<UtilitySectionEntity>

    @Query("SELECT * FROM utility_line_items WHERE sectionId = :sectionId ORDER BY sortOrder")
    suspend fun getLineItemsForSection(sectionId: Int): List<UtilityLineItemEntity>

    @Query("SELECT * FROM utility_line_items WHERE sectionId IN (SELECT id FROM utility_sections WHERE billId = :billId)")
    suspend fun getLineItemsForBill(billId: Int): List<UtilityLineItemEntity>

    @Query(
        """
        SELECT s.billId AS billId, COALESCE(SUM(li.amount), 0) AS total
        FROM utility_sections s
        LEFT JOIN utility_line_items li ON li.sectionId = s.id
        GROUP BY s.billId
    """,
    )
    suspend fun getBillGrandTotals(): List<BillGrandTotal>

    @Query("SELECT COUNT(*) FROM utility_bills")
    suspend fun getBillCount(): Int

    @Query("SELECT * FROM utility_bills")
    suspend fun getAllBillsForExport(): List<UtilityBillEntity>

    @Query("SELECT * FROM utility_sections")
    suspend fun getAllSectionsForExport(): List<UtilitySectionEntity>

    @Query("SELECT * FROM utility_line_items")
    suspend fun getAllLineItemsForExport(): List<UtilityLineItemEntity>

    @Query("SELECT * FROM utility_meter_readings WHERE propertyId = :propertyId ORDER BY groupName, meterName, sortOrder")
    suspend fun getAllMeterReadings(propertyId: Int): List<UtilityMeterReadingEntity>

    @Query("SELECT * FROM utility_meter_readings ORDER BY groupName, meterName, sortOrder")
    suspend fun getAllMeterReadings(): List<UtilityMeterReadingEntity>

    @Query(
        """
        SELECT * FROM utility_meter_readings
        WHERE propertyId = :propertyId
          AND meterName = :meterName
          AND (:groupName = '' OR groupName = :groupName)
        ORDER BY sortOrder ASC
        """,
    )
    suspend fun getMeterReadingsHistory(
        propertyId: Int,
        groupName: String,
        meterName: String,
    ): List<UtilityMeterReadingEntity>

    @Query(
        """
        SELECT COALESCE(MAX(sortOrder), -1) FROM utility_meter_readings
        WHERE propertyId = :propertyId
          AND meterName = :meterName
          AND (:groupName = '' OR groupName = :groupName)
        """,
    )
    suspend fun getMaxReadingSortOrder(propertyId: Int, groupName: String, meterName: String): Int

    @Query("SELECT * FROM utility_meter_readings")
    suspend fun getAllMeterReadingsForExport(): List<UtilityMeterReadingEntity>

    @Query("SELECT * FROM utility_meter_info WHERE propertyId = :propertyId ORDER BY sortOrder, groupName, meterName")
    suspend fun getAllMeterInfo(propertyId: Int): List<UtilityMeterInfoEntity>

    @Query("SELECT * FROM utility_meter_info ORDER BY sortOrder, groupName, meterName")
    suspend fun getAllMeterInfo(): List<UtilityMeterInfoEntity>

    @Query("SELECT * FROM utility_meter_info WHERE id = :id")
    suspend fun getMeterInfoById(id: Int): UtilityMeterInfoEntity?

    @Query(
        """
        SELECT * FROM utility_meter_info
        WHERE propertyId = :propertyId AND meterName = :meterName AND groupName = :groupName LIMIT 1
        """,
    )
    suspend fun getMeterInfoByKey(propertyId: Int, groupName: String, meterName: String): UtilityMeterInfoEntity?

    @Query("SELECT * FROM utility_meter_info")
    suspend fun getAllMeterInfoForExport(): List<UtilityMeterInfoEntity>

    @Query("SELECT COUNT(*) FROM utility_template_sections WHERE propertyId = :propertyId")
    suspend fun getTemplateSectionCount(propertyId: Int): Int

    @Query("SELECT * FROM utility_template_sections WHERE propertyId = :propertyId ORDER BY sortOrder")
    suspend fun getAllTemplateSections(propertyId: Int): List<UtilityTemplateSectionEntity>

    @Query("SELECT * FROM utility_template_sections ORDER BY sortOrder")
    suspend fun getAllTemplateSections(): List<UtilityTemplateSectionEntity>

    @Query("SELECT * FROM utility_template_sections WHERE id = :id")
    suspend fun getTemplateSectionById(id: Int): UtilityTemplateSectionEntity?

    @Query("SELECT COALESCE(MAX(sortOrder), -1) FROM utility_template_sections WHERE propertyId = :propertyId")
    suspend fun getMaxTemplateSectionSortOrder(propertyId: Int): Int

    @Query("SELECT * FROM utility_template_lines WHERE sectionId = :sectionId ORDER BY sortOrder")
    suspend fun getTemplateLinesForSection(sectionId: Int): List<UtilityTemplateLineEntity>

    @Query("SELECT * FROM utility_template_lines WHERE id = :id")
    suspend fun getTemplateLineById(id: Int): UtilityTemplateLineEntity?

    @Query("SELECT * FROM utility_template_lines ORDER BY sectionId, sortOrder")
    suspend fun getAllTemplateLines(): List<UtilityTemplateLineEntity>

    @Query("SELECT COALESCE(MAX(sortOrder), -1) FROM utility_template_lines WHERE sectionId = :sectionId")
    suspend fun getMaxTemplateLineSortOrder(sectionId: Int): Int

    @Query("SELECT * FROM utility_template_sections")
    suspend fun getAllTemplateSectionsForExport(): List<UtilityTemplateSectionEntity>

    @Query("SELECT * FROM utility_template_lines")
    suspend fun getAllTemplateLinesForExport(): List<UtilityTemplateLineEntity>

    @Query("SELECT * FROM utility_tariffs WHERE templateLineId = :lineId LIMIT 1")
    suspend fun getTariffForLine(lineId: Int): UtilityTariffEntity?

    @Query("SELECT * FROM utility_tariffs")
    suspend fun getAllTariffs(): List<UtilityTariffEntity>

    @Query("SELECT * FROM utility_tariffs")
    suspend fun getAllTariffsForExport(): List<UtilityTariffEntity>

    @Query(
        """
        SELECT COUNT(*) FROM utility_template_lines
        WHERE lineMode = 'qty_tariff'
          AND sectionId IN (SELECT id FROM utility_template_sections WHERE propertyId = :propertyId)
    """,
    )
    suspend fun getTemplateTariffLineCount(propertyId: Int): Int

    @Query(
        """
        SELECT COUNT(*) FROM utility_tariffs t
        INNER JOIN utility_template_lines l ON l.id = t.templateLineId
        INNER JOIN utility_template_sections s ON s.id = l.sectionId
        WHERE s.propertyId = :propertyId AND t.tariff > 0
    """,
    )
    suspend fun getFilledTariffCount(propertyId: Int): Int

    @Query(
        """
        SELECT COUNT(*) FROM (
            SELECT 1 FROM utility_meter_readings r
            WHERE r.propertyId = :propertyId
              AND NOT EXISTS (
                SELECT 1 FROM utility_meter_info i
                WHERE i.propertyId = r.propertyId
                  AND i.groupName = r.groupName
                  AND i.meterName = r.meterName
            )
            GROUP BY r.groupName, r.meterName
        )
    """,
    )
    suspend fun countReadingsWithoutCatalogEntry(propertyId: Int): Int

    @Query(
        """
        UPDATE utility_bills
        SET budgetPaidAt = NULL,
            budgetPaymentSummary = '',
            budgetRemainderSummary = '',
            budgetPaymentGroupId = NULL
        WHERE id = :billId
        """,
    )
    suspend fun clearBudgetPayment(billId: Int)

    @Query("DELETE FROM utility_bills WHERE id = :id")
    suspend fun deleteBill(id: Int)

    @Query("DELETE FROM utility_line_items WHERE id = :id")
    suspend fun deleteLineItemById(id: Int)

    @Query("DELETE FROM utility_line_items WHERE sectionId IN (SELECT id FROM utility_sections WHERE billId = :billId)")
    suspend fun deleteLineItemsForBill(billId: Int)

    @Query("DELETE FROM utility_sections WHERE billId = :billId")
    suspend fun deleteSectionsForBill(billId: Int)

    @Query("DELETE FROM utility_line_items")
    suspend fun deleteAllLineItems()

    @Query("DELETE FROM utility_sections")
    suspend fun deleteAllSections()

    @Query("DELETE FROM utility_bills")
    suspend fun deleteAllBills()

    @Query("DELETE FROM utility_meter_readings WHERE id = :id")
    suspend fun deleteMeterReadingById(id: Int)

    @Query(
        """
        DELETE FROM utility_meter_readings
        WHERE propertyId = :propertyId
          AND meterName = :meterName
          AND (:groupName = '' OR groupName = :groupName)
        """,
    )
    suspend fun deleteReadingsForMeter(propertyId: Int, groupName: String, meterName: String)

    @Query("DELETE FROM utility_meter_readings")
    suspend fun deleteAllMeterReadings()

    @Query("DELETE FROM utility_meter_info WHERE id = :id")
    suspend fun deleteMeterInfoById(id: Int)

    @Query("DELETE FROM utility_meter_info")
    suspend fun deleteAllMeterInfo()

    @Query("DELETE FROM utility_template_sections WHERE id = :id")
    suspend fun deleteTemplateSection(id: Int)

    @Query("DELETE FROM utility_template_lines WHERE id = :id")
    suspend fun deleteTemplateLine(id: Int)

    @Query("DELETE FROM utility_template_lines WHERE sectionId = :sectionId")
    suspend fun deleteTemplateLinesForSection(sectionId: Int)

    @Query("DELETE FROM utility_template_lines")
    suspend fun deleteAllTemplateLines()

    @Query("DELETE FROM utility_template_sections")
    suspend fun deleteAllTemplateSections()

    @Query("DELETE FROM utility_tariffs WHERE templateLineId = :lineId")
    suspend fun deleteTariffForLine(lineId: Int)

    @Query("DELETE FROM utility_tariffs WHERE templateLineId IN (SELECT id FROM utility_template_lines WHERE sectionId = :sectionId)")
    suspend fun deleteTariffsForSection(sectionId: Int)

    @Query("DELETE FROM utility_bills WHERE propertyId = :propertyId")
    suspend fun deleteBillsForProperty(propertyId: Int)

    @Query("DELETE FROM utility_meter_readings WHERE propertyId = :propertyId")
    suspend fun deleteMeterReadingsForProperty(propertyId: Int)

    @Query("DELETE FROM utility_meter_info WHERE propertyId = :propertyId")
    suspend fun deleteMeterInfoForProperty(propertyId: Int)

    @Query("DELETE FROM utility_template_sections WHERE propertyId = :propertyId")
    suspend fun deleteTemplateSectionsForProperty(propertyId: Int)

    @Query("SELECT * FROM utility_properties")
    suspend fun getAllPropertiesForExport(): List<UtilityPropertyEntity>

    @Query("DELETE FROM utility_properties")
    suspend fun deleteAllProperties()

    @Query("DELETE FROM utility_tariffs")
    suspend fun deleteAllTariffs()
}
