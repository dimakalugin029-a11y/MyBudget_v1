package ru.mybudget.app.auto

import ru.mybudget.app.data.VehicleFuelLogEntity
import ru.mybudget.app.data.VehicleInsuranceEntity
import ru.mybudget.app.data.VehicleRepairEntity
import ru.mybudget.app.data.VehicleServiceLogEntity

object AutoPaymentMethod {
    const val UNPAID = "unpaid"
    const val BUDGET = "budget"
    const val CASH = "cash"
    val options = listOf(UNPAID, BUDGET, CASH)

    // Old JSON backups have no paymentMethod; the transaction link remains authoritative.
    fun resolve(method: String?, transactionId: Long?): String = when {
        transactionId != null -> BUDGET
        method == CASH -> CASH
        method == BUDGET -> BUDGET
        else -> UNPAID
    }
}

enum class AutoEntryType { SERVICE, INSURANCE, FUEL, REPAIR }

data class AutoEntry(
    val type: AutoEntryType,
    val id: Int = 0,
    val vehicleId: Int,
    val dateEpochDay: Long,
    val amount: Double,
    val categoryId: Long = -1,
    val paymentMethod: String = AutoPaymentMethod.UNPAID,
    val paidTransactionId: Long? = null,
    val description: String = "",
    val mileageKm: Int? = null,
    val endDateEpochDay: Long? = null,
    val liters: Double = 0.0,
    val pricePerLiter: Double = 0.0,
    val createdAt: Long = System.currentTimeMillis(),
    val isArchived: Boolean = false,
) {
    val paid get() = paymentMethod != AutoPaymentMethod.UNPAID

    companion object {
        fun from(log: VehicleServiceLogEntity) = AutoEntry(AutoEntryType.SERVICE, log.id, log.vehicleId,
            log.dateEpochDay, log.amount, log.categoryId,
            AutoPaymentMethod.resolve(log.paymentMethod, log.paidTransactionId), log.paidTransactionId,
            log.workDescription, log.mileageKm, createdAt = log.createdAt, isArchived = log.isArchived)

        fun from(item: VehicleInsuranceEntity) = AutoEntry(AutoEntryType.INSURANCE, item.id, item.vehicleId,
            item.startDateEpochDay, item.amount, item.categoryId,
            AutoPaymentMethod.resolve(item.paymentMethod, item.paidTransactionId), item.paidTransactionId,
            endDateEpochDay = item.endDateEpochDay, createdAt = item.createdAt, isArchived = item.isArchived)

        fun from(log: VehicleFuelLogEntity) = AutoEntry(AutoEntryType.FUEL, log.id, log.vehicleId,
            log.dateEpochDay, log.amount, log.categoryId,
            AutoPaymentMethod.resolve(log.paymentMethod, log.paidTransactionId), log.paidTransactionId,
            mileageKm = log.mileageKm, liters = log.liters, pricePerLiter = log.pricePerLiter,
            createdAt = log.createdAt, isArchived = log.isArchived)

        fun from(log: VehicleRepairEntity) = AutoEntry(AutoEntryType.REPAIR, log.id, log.vehicleId,
            log.dateEpochDay, log.amount, log.categoryId,
            AutoPaymentMethod.resolve(log.paymentMethod, log.paidTransactionId), log.paidTransactionId,
            log.orderDescription, log.mileageKm, createdAt = log.createdAt, isArchived = log.isArchived)
    }
}
