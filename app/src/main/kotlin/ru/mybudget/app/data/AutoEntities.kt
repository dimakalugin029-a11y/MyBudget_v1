package ru.mybudget.app.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "vehicles")
data class VehicleEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    val sortOrder: Int = 0,
)

@Entity(
    tableName = "vehicle_service_logs",
    foreignKeys = [
        ForeignKey(
            entity = VehicleEntity::class,
            parentColumns = ["id"],
            childColumns = ["vehicleId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("vehicleId")],
)
data class VehicleServiceLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val vehicleId: Int,
    val dateEpochDay: Long,
    val mileageKm: Int? = null,
    val workDescription: String = "",
    val amount: Double,
    val categoryId: Long,
    val paidTransactionId: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "vehicle_insurances",
    foreignKeys = [
        ForeignKey(
            entity = VehicleEntity::class,
            parentColumns = ["id"],
            childColumns = ["vehicleId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("vehicleId")],
)
data class VehicleInsuranceEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val vehicleId: Int,
    val startDateEpochDay: Long,
    val endDateEpochDay: Long,
    val amount: Double,
    val categoryId: Long,
    val paidTransactionId: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "vehicle_fuel_logs",
    foreignKeys = [
        ForeignKey(
            entity = VehicleEntity::class,
            parentColumns = ["id"],
            childColumns = ["vehicleId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("vehicleId")],
)
data class VehicleFuelLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val vehicleId: Int,
    val dateEpochDay: Long,
    val liters: Double,
    val pricePerLiter: Double,
    val amount: Double,
    val mileageKm: Int? = null,
    val categoryId: Long,
    val paidTransactionId: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "vehicle_repairs",
    foreignKeys = [
        ForeignKey(
            entity = VehicleEntity::class,
            parentColumns = ["id"],
            childColumns = ["vehicleId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("vehicleId")],
)
data class VehicleRepairEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val vehicleId: Int,
    val dateEpochDay: Long,
    val mileageKm: Int? = null,
    val orderDescription: String = "",
    val amount: Double,
    val categoryId: Long,
    val paidTransactionId: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
)
