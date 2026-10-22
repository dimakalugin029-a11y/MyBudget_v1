package ru.mybudget.app.data

import androidx.room.withTransaction
import ru.mybudget.app.MoneyFormat
import ru.mybudget.app.auto.AutoEntry
import ru.mybudget.app.auto.AutoEntryType
import ru.mybudget.app.auto.AutoPaymentMethod

class AutoRepository(private val database: BudgetDatabase) {
    private val autoDao = database.autoDao()
    private val budgetDao = database.budgetDao()

    suspend fun getVehicleCount(): Int = autoDao.getVehicleCount()
    suspend fun getAllVehicles() = autoDao.getAllVehicles()
    suspend fun ensureDefaultVehicle(name: String): VehicleEntity = database.withTransaction {
        val existing = autoDao.getAllVehicles()
        if (existing.isNotEmpty()) return@withTransaction existing.first()
        autoDao.insertVehicle(VehicleEntity(name = name))
        autoDao.getAllVehicles().first()
    }
    suspend fun insertVehicle(name: String): Int =
        autoDao.insertVehicle(VehicleEntity(name = name, sortOrder = autoDao.getMaxVehicleSortOrder() + 1)).toInt()
    suspend fun updateVehicle(vehicle: VehicleEntity) = autoDao.updateVehicle(vehicle)
    suspend fun setVehicleArchived(id:Int, archived:Boolean) = database.withTransaction {
        requireNotNull(autoDao.getVehicleById(id)) { "Автомобиль уже удалён" }
        autoDao.setVehicleArchived(id,archived)
    }
    suspend fun getServiceLogs(vehicleId: Int) = autoDao.getServiceLogs(vehicleId)
    suspend fun getInsurances(vehicleId: Int) = autoDao.getInsurances(vehicleId)
    suspend fun getAllInsurances() = autoDao.getAllInsurances()
    suspend fun getFuelLogs(vehicleId: Int) = autoDao.getFuelLogs(vehicleId)
    suspend fun getRepairs(vehicleId: Int) = autoDao.getRepairs(vehicleId)

    suspend fun getEntries(vehicleId: Int? = null): List<AutoEntry> = database.withTransaction {
        (autoDao.getAllServiceLogsForExport().map { AutoEntry.from(it) } +
            autoDao.getAllInsurances().map { AutoEntry.from(it) } +
            autoDao.getAllFuelLogsForExport().map { AutoEntry.from(it) } +
            autoDao.getAllRepairsForExport().map { AutoEntry.from(it) })
            .filter { vehicleId == null || it.vehicleId == vehicleId }
    }

    private suspend fun getEntry(type: AutoEntryType, id: Int): AutoEntry? = when (type) {
        AutoEntryType.SERVICE -> autoDao.getServiceLogById(id)?.let { AutoEntry.from(it) }
        AutoEntryType.INSURANCE -> autoDao.getInsuranceById(id)?.let { AutoEntry.from(it) }
        AutoEntryType.FUEL -> autoDao.getFuelLogById(id)?.let { AutoEntry.from(it) }
        AutoEntryType.REPAIR -> autoDao.getRepairById(id)?.let { AutoEntry.from(it) }
    }

    /** The event, its payment link and category balances commit or roll back together. */
    suspend fun saveEntry(entry: AutoEntry, requestId: String? = null): Int = database.withTransaction {
        if (requestId != null) {
            require(requestId.isNotBlank())
            autoDao.getSaveCommand(requestId)?.let { previous ->
                require(previous.entryType == entry.type.name && previous.vehicleId == entry.vehicleId &&
                    (entry.id == 0 || entry.id == previous.entryId)) { "Несовместимый повтор команды" }
                return@withTransaction previous.entryId
            }
        }
        require(entry.amount.isFinite() && entry.amount >= 0.0) { "Укажите корректную сумму" }
        require(entry.paymentMethod in AutoPaymentMethod.options) { "Выберите способ оплаты" }
        val amount = MoneyFormat.roundMoney(entry.amount)
        require(amount.isFinite()) { "Слишком большая сумма" }
        if (entry.paid) require(amount > 0.0) { "Для оплаты укажите сумму больше нуля" }
        val vehicle=requireNotNull(autoDao.getVehicleById(entry.vehicleId)) { "Автомобиль уже удалён" }
        require(!vehicle.isArchived && !entry.isArchived) { "Сначала верните автомобиль и событие из архива" }
        if (entry.type == AutoEntryType.INSURANCE) {
            require(entry.endDateEpochDay != null && entry.endDateEpochDay >= entry.dateEpochDay) {
                "Дата окончания ОСАГО должна быть не раньше даты начала"
            }
        }
        if (entry.type == AutoEntryType.FUEL) {
            require(entry.liters.isFinite() && entry.liters > 0.0 &&
                entry.pricePerLiter.isFinite() && entry.pricePerLiter >= 0.0) { "Проверьте литры и цену топлива" }
        }
        val current = if (entry.id == 0) null else requireNotNull(getEntry(entry.type, entry.id)) {
            "Запись уже удалена. Обновите список"
        }
        require(current?.isArchived != true) { "Сначала верните событие из архива" }
        require(current == null || current.vehicleId == entry.vehicleId) { "Автомобиль записи изменился" }
        val previousTransaction = current?.paidTransactionId?.let { budgetDao.getTransactionById(it.toInt()) }
        val transactionId: Long?
        if (entry.paymentMethod == AutoPaymentMethod.BUDGET) {
            val category = requireNotNull(budgetDao.getCategoryById(entry.categoryId.toInt())) {
                "Статья недоступна. Выберите другую статью"
            }
            val description = entry.description.ifBlank {
                when (entry.type) {
                    AutoEntryType.SERVICE -> "ТО"
                    AutoEntryType.INSURANCE -> "ОСАГО"
                    AutoEntryType.FUEL -> "Бензин"
                    AutoEntryType.REPAIR -> "Ремонт"
                }
            }
            if (previousTransaction != null) {
                budgetDao.correctTransaction(previousTransaction.id, category.id, amount, "expense",
                    description, previousTransaction.participantLabel)
                val corrected = requireNotNull(budgetDao.getTransactionById(previousTransaction.id))
                budgetDao.updateTransaction(corrected.copy(date = entry.dateEpochDay * MILLIS_PER_DAY))
                transactionId = corrected.id.toLong()
            } else {
                transactionId = budgetDao.insertTransaction(TransactionEntity(categoryId = category.id,
                    amount = amount, type = "expense", description = description,
                    date = entry.dateEpochDay * MILLIS_PER_DAY))
                budgetDao.applyBalanceDelta(category.id, -amount, "expense", "auto-tx-$transactionId")
            }
        } else {
            if (previousTransaction != null) budgetDao.revertAndDeleteTransaction(previousTransaction)
            transactionId = null
        }
        val saved = entry.copy(amount = amount, paidTransactionId = transactionId,
            categoryId = if (entry.paymentMethod == AutoPaymentMethod.BUDGET) entry.categoryId else -1,
            createdAt = current?.createdAt ?: entry.createdAt)
        val savedId = when (saved.type) {
            AutoEntryType.SERVICE -> {
                val log = VehicleServiceLogEntity(id = saved.id, vehicleId = saved.vehicleId,
                    dateEpochDay = saved.dateEpochDay, amount = saved.amount, categoryId = saved.categoryId,
                    mileageKm = saved.mileageKm, workDescription = saved.description,
                    paidTransactionId = transactionId, createdAt = saved.createdAt, paymentMethod = saved.paymentMethod)
                if (saved.id == 0) autoDao.insertServiceLog(log).toInt() else { autoDao.updateServiceLog(log); saved.id }
            }
            AutoEntryType.INSURANCE -> {
                val item = VehicleInsuranceEntity(id = saved.id, vehicleId = saved.vehicleId,
                    startDateEpochDay = saved.dateEpochDay, endDateEpochDay = requireNotNull(saved.endDateEpochDay),
                    amount = saved.amount, categoryId = saved.categoryId, paidTransactionId = transactionId,
                    createdAt = saved.createdAt, paymentMethod = saved.paymentMethod)
                if (saved.id == 0) autoDao.insertInsurance(item).toInt() else { autoDao.updateInsurance(item); saved.id }
            }
            AutoEntryType.FUEL -> {
                val log = VehicleFuelLogEntity(id = saved.id, vehicleId = saved.vehicleId,
                    dateEpochDay = saved.dateEpochDay, liters = saved.liters, pricePerLiter = saved.pricePerLiter,
                    amount = saved.amount, mileageKm = saved.mileageKm, categoryId = saved.categoryId,
                    paidTransactionId = transactionId, createdAt = saved.createdAt, paymentMethod = saved.paymentMethod)
                if (saved.id == 0) autoDao.insertFuelLog(log).toInt() else { autoDao.updateFuelLog(log); saved.id }
            }
            AutoEntryType.REPAIR -> {
                val log = VehicleRepairEntity(id = saved.id, vehicleId = saved.vehicleId,
                    dateEpochDay = saved.dateEpochDay, mileageKm = saved.mileageKm, orderDescription = saved.description,
                    amount = saved.amount, categoryId = saved.categoryId, paidTransactionId = transactionId,
                    createdAt = saved.createdAt, paymentMethod = saved.paymentMethod)
                if (saved.id == 0) autoDao.insertRepair(log).toInt() else { autoDao.updateRepair(log); saved.id }
            }
        }
        if (requestId != null) autoDao.insertSaveCommand(AutoSaveCommandEntity(requestId, entry.type.name, savedId, entry.vehicleId))
        savedId
    }

    /** Archive preserves payments, mileage and statistical contribution. */
    suspend fun setEntryArchived(type:AutoEntryType,id:Int,archived:Boolean) = database.withTransaction {
        val entry=requireNotNull(getEntry(type,id)) { "Запись уже удалена. Обновите список" }
        require(autoDao.getVehicleById(entry.vehicleId)?.isArchived == false) { "Сначала верните автомобиль из архива" }
        when(type) {
            AutoEntryType.SERVICE -> autoDao.setServiceArchived(id,archived)
            AutoEntryType.INSURANCE -> autoDao.setInsuranceArchived(id,archived)
            AutoEntryType.FUEL -> autoDao.setFuelArchived(id,archived)
            AutoEntryType.REPAIR -> autoDao.setRepairArchived(id,archived)
        }
    }

    /** A stale confirmation must not cancel a subsequently corrected payment. */
    suspend fun cancelEntry(expected:AutoEntry) = database.withTransaction {
        val current=getEntry(expected.type,expected.id) ?: return@withTransaction
        require(current == expected) { "Запись изменилась. Откройте её заново" }
        require(!current.isArchived && autoDao.getVehicleById(current.vehicleId)?.isArchived == false) {
            "Сначала верните автомобиль и событие из архива"
        }
        current.paidTransactionId?.let { id ->
            budgetDao.getTransactionById(id.toInt())?.let { transaction ->
                require(transaction.type == "expense") { "Связанная операция не является расходом" }
                budgetDao.revertAndDeleteTransaction(transaction)
            }
        }
        when(current.type) {
            AutoEntryType.SERVICE -> autoDao.deleteServiceLog(current.id)
            AutoEntryType.INSURANCE -> autoDao.deleteInsurance(current.id)
            AutoEntryType.FUEL -> autoDao.deleteFuelLog(current.id)
            AutoEntryType.REPAIR -> autoDao.deleteRepair(current.id)
        }
    }

    suspend fun getCategoryBalance(categoryId: Int): Double =
        budgetDao.getCategoryById(categoryId)?.currentBalance ?: 0.0

    suspend fun fuelConsumptionPer100Km(vehicleId: Int): Double? {
        val logs = autoDao.getFuelLogs(vehicleId).sortedBy { it.mileageKm }
        if (logs.size < 2 || logs.any { it.mileageKm == null }) return null
        val distance = logs.last().mileageKm!! - logs.first().mileageKm!!
        if (distance <= 0) return null
        return logs.drop(1).sumOf { it.liters }.takeIf { it > 0.0 }?.let { it * 100.0 / distance }
    }

    companion object {
        const val MILLIS_PER_DAY = 24L * 60 * 60 * 1000
        const val REMIND_DAYS_LONG = 30L
        const val REMIND_DAYS_SHORT = 7L
        fun daysUntil(endDateEpochDay: Long, todayEpochDay: Long): Long = endDateEpochDay - todayEpochDay
        fun isReminderDue(endDateEpochDay: Long, todayEpochDay: Long): Boolean =
            daysUntil(endDateEpochDay, todayEpochDay) in 0..REMIND_DAYS_LONG
    }
}
