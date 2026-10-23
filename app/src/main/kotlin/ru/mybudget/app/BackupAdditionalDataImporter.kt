package ru.mybudget.app

import com.google.gson.Gson
import ru.mybudget.app.data.AutoDao
import ru.mybudget.app.data.BudgetDao
import ru.mybudget.app.auto.AutoPaymentMethod

/** Called inside the enclosing backup import transaction, after categories and transactions exist. */
internal object BackupAdditionalDataImporter {
    suspend fun import(
        data: BackupData,
        dao: BudgetDao,
        autoDao: AutoDao,
        profileIdMap: Map<Int, Int>,
        fallbackProfileId: Int,
        categoryIdMap: Map<Int, Int>,
        transactionIdMap: Map<Long, Long>,
        incomeSourceIdMap: Map<Int, Int>,
    ) {
        fun budgetId(id: Int) = profileIdMap[id] ?: fallbackProfileId
        for (transaction in data.transactions) {
            val newId = transactionIdMap[transaction.id.toLong()]?.toInt() ?: continue
            val current = dao.getTransactionById(newId) ?: continue
            val sourceId = transaction.plannedIncomeSourceId?.let { incomeSourceIdMap[it] }
            val source = sourceId?.let { dao.getIncomeSourceById(it) }
            val category = dao.getCategoryById(current.categoryId)
            val month = transaction.plannedIncomeMonth?.let { runCatching { java.time.YearMonth.parse(it).toString() }.getOrNull() }
            val valid = current.type == "income" && source != null && source.budgetId == category?.budgetId && month != null
            dao.updateTransaction(current.copy(plannedIncomeSourceId = sourceId.takeIf { valid },
                plannedIncomeMonth = month.takeIf { valid },incomePlanFinal=valid && transaction.incomePlanFinal))
        }
        for (plan in data.monthlyIncomePlans) {
            // Deleted income sources can leave obsolete monthly overrides behind.
            val sourceId = incomeSourceIdMap[plan.sourceId] ?: continue
            dao.upsertMonthlyIncomePlan(plan.copy(sourceId = sourceId, budgetId = budgetId(plan.budgetId)))
        }
        for (snapshot in data.forecastSnapshots) {
            val lines = ForecastSnapshotCodec.decode(snapshot.linesJson)
            val remapped = lines?.copy(
                incomes = lines.incomes.map { it.copy(sourceId = incomeSourceIdMap[it.sourceId] ?: 0) },
                expenses = lines.expenses.map { it.copy(categoryId = categoryIdMap[it.categoryId] ?: 0) },
            )
            dao.upsertForecastSnapshot(snapshot.copy(
                id = 0,
                budgetId = budgetId(snapshot.budgetId),
                linesJson = remapped?.let { Gson().toJson(it) } ?: snapshot.linesJson,
            ))
        }

        val vehicleIdMap = mutableMapOf<Int, Int>()
        for (vehicle in data.vehicles) {
            vehicleIdMap[vehicle.id] = autoDao.insertVehicle(vehicle.copy(id = 0)).toInt()
        }
        fun vehicleId(id: Int) = requireNotNull(vehicleIdMap[id]) { "Missing vehicle in backup: $id" }
        // An archived/deleted category or transaction must never bind to an unrelated local ID.
        fun categoryId(id: Long) = categoryIdMap[id.toInt()]?.toLong() ?: -1L
        fun transactionId(id: Long?) = id?.let { transactionIdMap[it] }
        for (log in data.vehicleServiceLogs) {
            autoDao.insertServiceLog(log.copy(
                id = 0, vehicleId = vehicleId(log.vehicleId), categoryId = categoryId(log.categoryId),
                paidTransactionId = transactionId(log.paidTransactionId),
                paymentMethod = AutoPaymentMethod.resolve(log.paymentMethod, log.paidTransactionId),
            ))
        }
        for (insurance in data.vehicleInsurances) {
            autoDao.insertInsurance(insurance.copy(
                id = 0, vehicleId = vehicleId(insurance.vehicleId), categoryId = categoryId(insurance.categoryId),
                paidTransactionId = transactionId(insurance.paidTransactionId),
                paymentMethod = AutoPaymentMethod.resolve(insurance.paymentMethod, insurance.paidTransactionId),
                insuranceType=ru.mybudget.app.auto.InsuranceType.resolve(insurance.insuranceType),insuranceCompany=insurance.insuranceCompany.orEmpty(),policyNumber=insurance.policyNumber.orEmpty(),insurancePhone=insurance.insurancePhone.orEmpty(),
            ))
        }
        for (log in data.vehicleFuelLogs) {
            autoDao.insertFuelLog(log.copy(
                id = 0, vehicleId = vehicleId(log.vehicleId), categoryId = categoryId(log.categoryId),
                paidTransactionId = transactionId(log.paidTransactionId),
                paymentMethod = AutoPaymentMethod.resolve(log.paymentMethod, log.paidTransactionId),
                fuelType=log.fuelType.orEmpty(),fuelStation=log.fuelStation.orEmpty(),
            ))
        }
        for (repair in data.vehicleRepairs) {
            autoDao.insertRepair(repair.copy(
                id = 0, vehicleId = vehicleId(repair.vehicleId), categoryId = categoryId(repair.categoryId),
                paidTransactionId = transactionId(repair.paidTransactionId),
                paymentMethod = AutoPaymentMethod.resolve(repair.paymentMethod, repair.paidTransactionId),
            ))
        }
    }
}
