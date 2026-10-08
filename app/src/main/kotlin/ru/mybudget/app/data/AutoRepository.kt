package ru.mybudget.app.data

import ru.mybudget.app.BudgetApplication
import ru.mybudget.app.OverspendNotifier

class AutoRepository(
    private val autoDao: AutoDao,
    private val budgetDao: BudgetDao,
) {
    suspend fun getVehicleCount(): Int = autoDao.getVehicleCount()

    suspend fun ensureDefaultVehicle(name: String): VehicleEntity {
        val existing = autoDao.getAllVehicles()
        if (existing.isNotEmpty()) return existing.first()
        autoDao.insertVehicle(VehicleEntity(name = name))
        return autoDao.getAllVehicles().first()
    }

    suspend fun insertVehicle(name: String): Int {
        val sortOrder = autoDao.getMaxVehicleSortOrder() + 1
        return autoDao.insertVehicle(VehicleEntity(name = name, sortOrder = sortOrder)).toInt()
    }

    suspend fun updateVehicle(vehicle: VehicleEntity) = autoDao.updateVehicle(vehicle)

    suspend fun deleteVehicle(id: Int) = autoDao.deleteVehicle(id)

    suspend fun getAllVehicles(): List<VehicleEntity> = autoDao.getAllVehicles()

    suspend fun getServiceLogs(vehicleId: Int) = autoDao.getServiceLogs(vehicleId)
    suspend fun getInsurances(vehicleId: Int) = autoDao.getInsurances(vehicleId)
    suspend fun getAllInsurances() = autoDao.getAllInsurances()
    suspend fun getFuelLogs(vehicleId: Int) = autoDao.getFuelLogs(vehicleId)
    suspend fun getRepairs(vehicleId: Int) = autoDao.getRepairs(vehicleId)

    suspend fun deleteServiceLog(id: Int) = autoDao.deleteServiceLog(id)
    suspend fun deleteInsurance(id: Int) = autoDao.deleteInsurance(id)
    suspend fun deleteFuelLog(id: Int) = autoDao.deleteFuelLog(id)
    suspend fun deleteRepair(id: Int) = autoDao.deleteRepair(id)

    suspend fun updateServiceLog(log: VehicleServiceLogEntity) = autoDao.updateServiceLog(log)
    suspend fun updateInsurance(insurance: VehicleInsuranceEntity) = autoDao.updateInsurance(insurance)
    suspend fun updateFuelLog(log: VehicleFuelLogEntity) = autoDao.updateFuelLog(log)
    suspend fun updateRepair(repair: VehicleRepairEntity) = autoDao.updateRepair(repair)

    suspend fun payExpense(categoryId: Int, amount: Double, description: String, dateEpochDay: Long): Long {
        val date = dateEpochDay * MILLIS_PER_DAY
        val transactionId = budgetDao.insertTransaction(
            TransactionEntity(
                categoryId = categoryId,
                amount = amount,
                type = "expense",
                description = description,
                date = date,
            ),
        )
        budgetDao.applyBalanceDelta(categoryId, -amount)
        budgetDao.recordBalanceSnapshotForCategory(categoryId)
        OverspendNotifier.checkAfterExpense(BudgetApplication.instance, categoryId)
        return transactionId
    }

    suspend fun addServiceLog(
        log: VehicleServiceLogEntity,
        payNow: Boolean,
        description: String,
    ): Int {
        val entity = if (payNow) {
            val txId = payExpense(log.categoryId.toInt(), log.amount, description, log.dateEpochDay)
            log.copy(paidTransactionId = txId)
        } else {
            log
        }
        return autoDao.insertServiceLog(entity).toInt()
    }

    suspend fun addInsurance(
        insurance: VehicleInsuranceEntity,
        payNow: Boolean,
        description: String,
    ): Int {
        val entity = if (payNow) {
            val txId = payExpense(insurance.categoryId.toInt(), insurance.amount, description, insurance.startDateEpochDay)
            insurance.copy(paidTransactionId = txId)
        } else {
            insurance
        }
        return autoDao.insertInsurance(entity).toInt()
    }

    suspend fun addFuelLog(
        log: VehicleFuelLogEntity,
        payNow: Boolean,
        description: String,
    ): Int {
        val entity = if (payNow) {
            val txId = payExpense(log.categoryId.toInt(), log.amount, description, log.dateEpochDay)
            log.copy(paidTransactionId = txId)
        } else {
            log
        }
        return autoDao.insertFuelLog(entity).toInt()
    }

    suspend fun addRepair(
        repair: VehicleRepairEntity,
        payNow: Boolean,
        description: String,
    ): Int {
        val entity = if (payNow) {
            val txId = payExpense(repair.categoryId.toInt(), repair.amount, description, repair.dateEpochDay)
            repair.copy(paidTransactionId = txId)
        } else {
            repair
        }
        return autoDao.insertRepair(entity).toInt()
    }

    suspend fun markServiceLogPaid(id: Int, transactionId: Long) = autoDao.setServiceLogTransactionId(id, transactionId)
    suspend fun markInsurancePaid(id: Int, transactionId: Long) = autoDao.setInsuranceTransactionId(id, transactionId)
    suspend fun markFuelLogPaid(id: Int, transactionId: Long) = autoDao.setFuelLogTransactionId(id, transactionId)
    suspend fun markRepairPaid(id: Int, transactionId: Long) = autoDao.setRepairTransactionId(id, transactionId)

    suspend fun getCategoryBalance(categoryId: Int): Double {
        return budgetDao.getCategoryById(categoryId)?.currentBalance ?: 0.0
    }

    suspend fun fuelConsumptionPer100Km(vehicleId: Int): Double? {
        val logs = autoDao.getFuelLogs(vehicleId)
        if (logs.any { it.mileageKm == null }) return null
        val sorted = logs.sortedBy { it.mileageKm }
        if (sorted.size < 2) return null
        val first = sorted.first()
        val last = sorted.last()
        val distance = (last.mileageKm ?: return null) - (first.mileageKm ?: return null)
        if (distance <= 0) return null
        val liters = logs.drop(1).sumOf { it.liters }
        if (liters <= 0.0) return null
        return liters * 100.0 / distance
    }

    companion object {
        const val MILLIS_PER_DAY = 24L * 60 * 60 * 1000

        const val REMIND_DAYS_LONG = 30L
        const val REMIND_DAYS_SHORT = 7L

        fun daysUntil(endDateEpochDay: Long, todayEpochDay: Long): Long =
            endDateEpochDay - todayEpochDay

        fun isReminderDue(endDateEpochDay: Long, todayEpochDay: Long): Boolean {
            val days = daysUntil(endDateEpochDay, todayEpochDay)
            return days in 0..REMIND_DAYS_LONG
        }
    }
}
