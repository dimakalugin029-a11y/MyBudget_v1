package ru.mybudget.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface AutoDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertVehicle(vehicle: VehicleEntity): Long

    @Update
    suspend fun updateVehicle(vehicle: VehicleEntity)

    @Query("SELECT * FROM vehicles ORDER BY sortOrder, id")
    suspend fun getAllVehicles(): List<VehicleEntity>

    @Query("SELECT * FROM vehicles WHERE id = :id")
    suspend fun getVehicleById(id: Int): VehicleEntity?

    @Query("SELECT COUNT(*) FROM vehicles")
    suspend fun getVehicleCount(): Int

    @Query("SELECT COALESCE(MAX(sortOrder), -1) FROM vehicles")
    suspend fun getMaxVehicleSortOrder(): Int

    @Query("DELETE FROM vehicles WHERE id = :id")
    suspend fun deleteVehicle(id: Int)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertServiceLog(log: VehicleServiceLogEntity): Long

    @Update
    suspend fun updateServiceLog(log: VehicleServiceLogEntity)

    @Query("SELECT * FROM vehicle_service_logs WHERE vehicleId = :vehicleId ORDER BY dateEpochDay DESC, id DESC")
    suspend fun getServiceLogs(vehicleId: Int): List<VehicleServiceLogEntity>

    @Query("SELECT * FROM vehicle_service_logs WHERE id = :id")
    suspend fun getServiceLogById(id: Int): VehicleServiceLogEntity?

    @Query("DELETE FROM vehicle_service_logs WHERE id = :id")
    suspend fun deleteServiceLog(id: Int)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertInsurance(insurance: VehicleInsuranceEntity): Long

    @Update
    suspend fun updateInsurance(insurance: VehicleInsuranceEntity)

    @Query("SELECT * FROM vehicle_insurances WHERE vehicleId = :vehicleId ORDER BY endDateEpochDay DESC, id DESC")
    suspend fun getInsurances(vehicleId: Int): List<VehicleInsuranceEntity>

    @Query("SELECT * FROM vehicle_insurances WHERE id = :id")
    suspend fun getInsuranceById(id: Int): VehicleInsuranceEntity?

    @Query("SELECT * FROM vehicle_insurances ORDER BY endDateEpochDay ASC")
    suspend fun getAllInsurances(): List<VehicleInsuranceEntity>

    @Query("DELETE FROM vehicle_insurances WHERE id = :id")
    suspend fun deleteInsurance(id: Int)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertFuelLog(log: VehicleFuelLogEntity): Long

    @Update
    suspend fun updateFuelLog(log: VehicleFuelLogEntity)

    @Query("SELECT * FROM vehicle_fuel_logs WHERE vehicleId = :vehicleId ORDER BY dateEpochDay DESC, id DESC")
    suspend fun getFuelLogs(vehicleId: Int): List<VehicleFuelLogEntity>

    @Query("SELECT * FROM vehicle_fuel_logs WHERE id = :id")
    suspend fun getFuelLogById(id: Int): VehicleFuelLogEntity?

    @Query("DELETE FROM vehicle_fuel_logs WHERE id = :id")
    suspend fun deleteFuelLog(id: Int)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertRepair(log: VehicleRepairEntity): Long

    @Update
    suspend fun updateRepair(log: VehicleRepairEntity)

    @Query("SELECT * FROM vehicle_repairs WHERE vehicleId = :vehicleId ORDER BY dateEpochDay DESC, id DESC")
    suspend fun getRepairs(vehicleId: Int): List<VehicleRepairEntity>

    @Query("SELECT * FROM vehicle_repairs WHERE id = :id")
    suspend fun getRepairById(id: Int): VehicleRepairEntity?

    @Query("DELETE FROM vehicle_repairs WHERE id = :id")
    suspend fun deleteRepair(id: Int)

    @Query("UPDATE vehicle_service_logs SET paidTransactionId = :transactionId WHERE id = :logId")
    suspend fun setServiceLogTransactionId(logId: Int, transactionId: Long?)

    @Query("UPDATE vehicle_insurances SET paidTransactionId = :transactionId WHERE id = :insuranceId")
    suspend fun setInsuranceTransactionId(insuranceId: Int, transactionId: Long?)

    @Query("UPDATE vehicle_fuel_logs SET paidTransactionId = :transactionId WHERE id = :logId")
    suspend fun setFuelLogTransactionId(logId: Int, transactionId: Long?)

    @Query("UPDATE vehicle_repairs SET paidTransactionId = :transactionId WHERE id = :repairId")
    suspend fun setRepairTransactionId(repairId: Int, transactionId: Long?)
}
