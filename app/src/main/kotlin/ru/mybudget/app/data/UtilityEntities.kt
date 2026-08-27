package ru.mybudget.app.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "utility_properties")
data class UtilityPropertyEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    val sortOrder: Int = 0,
)

@Entity(
    tableName = "utility_bills",
    indices = [
        Index("propertyId"),
        Index(value = ["propertyId", "year", "month"], unique = true),
    ],
)
data class UtilityBillEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val propertyId: Int = 1,
    val year: Int,
    val month: Int,
    val apartmentArea: Double,
    val notes: String = "",
    val budgetPaidAt: Long? = null,
    val budgetPaymentSummary: String = "",
    val budgetRemainderSummary: String = "",
    val budgetPaymentGroupId: String? = null,
    val receiptPhotoUri: String? = null,
)

@Entity(
    tableName = "utility_bill_photos",
    foreignKeys = [
        ForeignKey(
            entity = UtilityBillEntity::class,
            parentColumns = ["id"],
            childColumns = ["billId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("billId")],
)
data class UtilityBillPhotoEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val billId: Int,
    val photoType: String,
    val storedUri: String,
    val sortOrder: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
) {
    companion object {
        const val TYPE_RECEIPT = "receipt"
        const val TYPE_METER = "meter"
    }
}

@Entity(
    tableName = "utility_sections",
    foreignKeys = [
        ForeignKey(
            entity = UtilityBillEntity::class,
            parentColumns = ["id"],
            childColumns = ["billId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("billId")],
)
data class UtilitySectionEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val billId: Int,
    val name: String,
    val sortOrder: Int = 0,
)

@Entity(
    tableName = "utility_line_items",
    foreignKeys = [
        ForeignKey(
            entity = UtilitySectionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sectionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("sectionId")],
)
data class UtilityLineItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val sectionId: Int,
    val groupLabel: String = "",
    val name: String,
    val quantity: Double? = null,
    val tariff: Double? = null,
    val amount: Double,
    val sortOrder: Int = 0,
)

@Entity(
    tableName = "utility_meter_readings",
    indices = [Index("propertyId")],
)
data class UtilityMeterReadingEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val propertyId: Int = 1,
    val groupName: String = "",
    val meterName: String,
    val periodLabel: String = "",
    val readingValue: Double,
    val consumption: Double? = null,
    val sortOrder: Int = 0,
)

@Entity(
    tableName = "utility_meter_info",
    indices = [Index(value = ["propertyId", "groupName", "meterName"], unique = true)],
)
data class UtilityMeterInfoEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val propertyId: Int = 1,
    val groupName: String = "",
    val meterName: String,
    val verificationDateLabel: String = "",
    val verificationEpochDay: Long? = null,
    val sortOrder: Int = 0,
)

@Entity(
    tableName = "utility_template_sections",
    indices = [Index("propertyId")],
)
data class UtilityTemplateSectionEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val propertyId: Int = 1,
    val name: String,
    val sortOrder: Int = 0,
)

@Entity(
    tableName = "utility_template_lines",
    foreignKeys = [
        ForeignKey(
            entity = UtilityTemplateSectionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sectionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("sectionId")],
)
data class UtilityTemplateLineEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val sectionId: Int,
    val groupLabel: String = "",
    val name: String,
    val lineMode: String = LINE_MODE_QTY_TARIFF,
    val sortOrder: Int = 0,
) {
    companion object {
        const val LINE_MODE_QTY_TARIFF = "qty_tariff"
        const val LINE_MODE_AMOUNT_ONLY = "amount_only"
    }
}

@Entity(
    tableName = "utility_tariffs",
    foreignKeys = [
        ForeignKey(
            entity = UtilityTemplateLineEntity::class,
            parentColumns = ["id"],
            childColumns = ["templateLineId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["templateLineId"], unique = true)],
)
data class UtilityTariffEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val templateLineId: Int,
    val tariff: Double,
    val updatedAt: Long = System.currentTimeMillis(),
)

data class UtilityTemplateSectionWithLines(
    val section: UtilityTemplateSectionEntity,
    val lines: List<UtilityTemplateLineEntity>,
)

data class UtilityTariffRow(
    val line: UtilityTemplateLineEntity,
    val sectionName: String,
    val tariff: Double?,
)

data class BillGrandTotal(
    val billId: Int,
    val total: Double,
)

data class BillPhotoCount(
    val billId: Int,
    val count: Int,
)
