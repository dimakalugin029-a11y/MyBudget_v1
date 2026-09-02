package ru.mybudget.app

import android.content.Context
import ru.mybudget.app.data.BudgetDao
import ru.mybudget.app.data.BudgetCategoryEntity
import ru.mybudget.app.data.BudgetProfileEntity
import ru.mybudget.app.data.MonthlyCategoryPlanEntity
import ru.mybudget.app.data.PaymentReminderEntity
import ru.mybudget.app.data.PlannedIncomeSourceEntity
import ru.mybudget.app.data.PlannedObligationEntity
import ru.mybudget.app.data.RecurringTransactionEntity
import ru.mybudget.app.data.SavingsGoalEntity
import ru.mybudget.app.data.TransactionEntity
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
import ru.mybudget.app.setup.ActiveBudgetPreferences
import ru.mybudget.app.setup.ActivePropertyPreferences
import ru.mybudget.app.setup.ParticipantPreferences
import ru.mybudget.app.utilities.UtilityPhotoStorage
import ru.mybudget.app.utilities.UtilityPropertyCopyHelper

internal class BackupMergeImporter(
    private val appContext: Context,
    private val dao: BudgetDao,
    private val utilityDao: UtilityDao,
) {
    suspend fun import(data: BackupData, photoBytes: Map<String, ByteArray>): Int {
        val defaultBudgetName = appContext.getString(R.string.budget_profiles_default_name)
        val existingProfiles = dao.getAllBudgetProfilesForExport()
        val profileIdMap = mutableMapOf<Int, Int>()
        existingProfiles.forEach { profile ->
            profileIdMap[profile.id] = profile.id
        }
        val profileByName = existingProfiles.associateBy { BackupMergeHelper.profileKey(it) }
        val profiles = data.budgetProfiles.ifEmpty {
            listOf(BudgetProfileEntity(name = defaultBudgetName))
        }
        for (profile in profiles) {
            val key = BackupMergeHelper.profileKey(profile)
            val existing = profileByName[key]
            if (existing != null) {
                if (profile.id > 0) profileIdMap[profile.id] = existing.id
                continue
            }
            val insertedId = dao.insertBudgetProfile(
                profile.copy(id = 0, name = profile.name.ifBlank { defaultBudgetName }, isActive = true),
            ).toInt()
            if (profile.id > 0) profileIdMap[profile.id] = insertedId
            profileIdMap[insertedId] = insertedId
        }
        val fallbackProfileId = ActiveBudgetPreferences.getActiveBudgetId(appContext)
            .takeIf { it in profileIdMap.values }
            ?: profileIdMap.values.firstOrNull()
            ?: ActiveBudgetPreferences.DEFAULT_BUDGET_ID
        val validProfileIds = profileIdMap.values.toSet()

        val existingCategories = dao.getAllCategoriesForExport()
        val categoryIdMap = mutableMapOf<Int, Int>()
        val categoryByKey = linkedMapOf<String, Int>()
        var nextCategoryId = existingCategories.maxOfOrNull { it.id } ?: 0
        existingCategories.forEach { category ->
            categoryIdMap[category.id] = category.id
            categoryByKey[BackupMergeHelper.categoryKey(category)] = category.id
        }
        for (category in data.categories) {
            val budgetId = remapId(category.budgetId, profileIdMap, validProfileIds, fallbackProfileId)
            val normalized = normalizeCategory(category).copy(budgetId = budgetId)
            val key = BackupMergeHelper.categoryKey(normalized)
            val existingId = categoryByKey[key]
            if (existingId != null) {
                if (category.id > 0) categoryIdMap[category.id] = existingId
                continue
            }
            nextCategoryId += 1
            val toInsert = normalized.copy(
                id = nextCategoryId,
                parentId = remapParentId(normalized.parentId, categoryIdMap),
            )
            dao.insertCategory(toInsert)
            categoryIdMap[nextCategoryId] = nextCategoryId
            if (category.id > 0) categoryIdMap[category.id] = nextCategoryId
            categoryByKey[key] = nextCategoryId
        }

        val insertedCategoryIds = categoryIdMap.values.toSet()
        for (transaction in data.transactions) {
            val categoryId = categoryIdMap[transaction.categoryId] ?: transaction.categoryId
            if (categoryId in insertedCategoryIds) {
                dao.insertTransaction(normalizeTransaction(transaction).copy(categoryId = categoryId))
            }
        }

        val obligationIdMap = mutableMapOf<Int, Int>()
        val incomeSourceIdMap = mutableMapOf<Int, Int>()
        for (incomeSource in data.plannedIncomeSources) {
            val budgetId = remapId(incomeSource.budgetId, profileIdMap, validProfileIds, fallbackProfileId)
            val sourceId = incomeSource.id
            val newId = dao.insertPlannedIncomeSource(incomeSource.copy(id = 0, budgetId = budgetId)).toInt()
            if (sourceId > 0) incomeSourceIdMap[sourceId] = newId
        }
        for (obligation in data.plannedObligations) {
            val budgetId = remapId(obligation.budgetId, profileIdMap, validProfileIds, fallbackProfileId)
            val sourceId = obligation.id
            val linkedIncomeSourceId = obligation.linkedIncomeSourceId?.let { incomeSourceIdMap[it] }
            val newId = dao.insertPlannedObligation(
                obligation.copy(
                    id = 0,
                    budgetId = budgetId,
                    obligationKind = PlannedObligationHelper.normalizeKind(obligation.obligationKind),
                    linkedIncomeSourceId = linkedIncomeSourceId,
                ),
            ).toInt()
            if (sourceId > 0) obligationIdMap[sourceId] = newId
        }
        fun remapObligationId(sourceId: Int?): Int? {
            if (sourceId == null || sourceId <= 0) return null
            return obligationIdMap[sourceId]
        }
        for (payment in data.obligationPayments) {
            val obligationId = remapObligationId(payment.obligationId) ?: continue
            runCatching {
                dao.insertObligationPayment(payment.copy(id = 0, obligationId = obligationId))
            }
        }
        for (snapshot in data.balanceSnapshots) {
            val budgetId = profileIdMap[snapshot.budgetId] ?: snapshot.budgetId
            runCatching {
                dao.upsertBalanceSnapshot(snapshot.copy(id = 0, budgetId = budgetId))
            }
        }
        for (reminder in data.reminders) {
            val categoryId = categoryIdMap[reminder.categoryId] ?: reminder.categoryId
            if (categoryId in insertedCategoryIds) {
                dao.insertReminder(
                    normalizeReminder(reminder).copy(
                        categoryId = categoryId,
                        obligationId = remapObligationId(reminder.obligationId),
                    ),
                )
            }
        }
        for (goal in data.savingsGoals) {
            val categoryId = categoryIdMap[goal.categoryId] ?: goal.categoryId
            if (categoryId in insertedCategoryIds) {
                dao.insertSavingsGoal(goal.copy(id = 0, categoryId = categoryId))
            }
        }
        for (recurring in data.recurringTransactions) {
            val categoryId = categoryIdMap[recurring.categoryId] ?: recurring.categoryId
            if (categoryId in insertedCategoryIds) {
                dao.insertRecurring(
                    recurring.copy(
                        id = 0,
                        categoryId = categoryId,
                        obligationId = remapObligationId(recurring.obligationId),
                    ),
                )
            }
        }
        for (plan in data.monthlyCategoryPlans) {
            val categoryId = categoryIdMap[plan.categoryId] ?: plan.categoryId
            if (categoryId in insertedCategoryIds) {
                val budgetId = remapId(plan.budgetId, profileIdMap, validProfileIds, fallbackProfileId)
                dao.upsertMonthlyPlan(
                    MonthlyCategoryPlanEntity(
                        year = plan.year,
                        month = plan.month,
                        categoryId = categoryId,
                        budgetId = budgetId,
                        plannedAmount = plan.plannedAmount,
                        isEnabled = plan.isEnabled,
                    ),
                )
            }
        }
        for (action in data.auditActions) {
            dao.insertAuditAction(
                action.copy(
                    id = 0,
                    actionType = action.actionType,
                    title = action.title,
                    description = action.description,
                    payload = action.payload,
                    createdAt = action.createdAt,
                    isReverted = action.isReverted,
                    revertedAt = action.revertedAt,
                ),
            )
        }

        val existingProperties = utilityDao.getAllPropertiesForExport()
        val propertyIdMap = mutableMapOf<Int, Int>()
        existingProperties.forEach { property ->
            propertyIdMap[property.id] = property.id
        }
        val propertyByName = existingProperties.associateBy { BackupMergeHelper.propertyKey(it) }
        if (data.utilityProperties.isNotEmpty()) {
            for (property in data.utilityProperties) {
                val existing = propertyByName[BackupMergeHelper.propertyKey(property)]
                if (existing != null) {
                    if (property.id > 0) propertyIdMap[property.id] = existing.id
                    continue
                }
                val newId = utilityDao.insertProperty(property.copy(id = 0)).toInt()
                if (property.id > 0) propertyIdMap[property.id] = newId
                propertyIdMap[newId] = newId
            }
        } else if (propertyIdMap.isEmpty()) {
            val defaultId = UtilityPropertyCopyHelper.ensureDefaultProperty(utilityDao)
            propertyIdMap[ActivePropertyPreferences.DEFAULT_PROPERTY_ID] = defaultId
        }
        fun remapPropertyId(sourceId: Int): Int {
            return propertyIdMap[sourceId]
                ?: propertyIdMap.values.firstOrNull()
                ?: ActivePropertyPreferences.DEFAULT_PROPERTY_ID
        }

        val existingBills = utilityDao.getAllBillsForExport()
        val billIdMap = mutableMapOf<Int, Int>()
        val billByKey = existingBills.associateBy { BackupMergeHelper.billKey(it.propertyId, it) }
        val insertedBillIds = mutableListOf<Int>()
        for (bill in data.utilityBills) {
            val propertyId = remapPropertyId(bill.propertyId)
            val key = BackupMergeHelper.billKey(propertyId, bill)
            val existing = billByKey[key]
            if (existing != null) {
                if (bill.id > 0) billIdMap[bill.id] = existing.id
                insertedBillIds.add(existing.id)
                continue
            }
            val newId = utilityDao.insertBill(bill.copy(id = 0, propertyId = propertyId)).toInt()
            insertedBillIds.add(newId)
            if (bill.id > 0) billIdMap[bill.id] = newId
        }

        for (photo in data.utilityBillPhotos) {
            val billId = billIdMap[photo.billId] ?: continue
            val bill = utilityDao.getBillById(billId) ?: continue
            val restoredUri = restorePhotoUri(photo, bill, photoBytes) ?: photo.storedUri
            utilityDao.insertBillPhoto(photo.copy(id = 0, billId = billId, storedUri = restoredUri))
        }

        val sectionIdMap = mutableMapOf<Int, Int>()
        for (section in data.utilitySections) {
            val billId = billIdMap[section.billId] ?: continue
            val newId = utilityDao.insertSection(section.copy(id = 0, billId = billId)).toInt()
            if (section.id > 0) sectionIdMap[section.id] = newId
        }
        for (line in data.utilityLineItems) {
            val sectionId = sectionIdMap[line.sectionId] ?: continue
            utilityDao.insertLineItem(line.copy(id = 0, sectionId = sectionId))
        }
        for (reading in data.utilityMeterReadings) {
            utilityDao.insertMeterReading(
                reading.copy(
                    id = 0,
                    propertyId = remapPropertyId(reading.propertyId),
                    meterName = reading.meterName.ifBlank { DEFAULT_METER_NAME },
                ),
            )
        }
        for (info in data.utilityMeterInfo) {
            utilityDao.insertMeterInfo(
                info.copy(
                    id = 0,
                    propertyId = remapPropertyId(info.propertyId),
                    meterName = info.meterName.ifBlank { DEFAULT_METER_NAME },
                ),
            )
        }
        val templateSectionIdMap = mutableMapOf<Int, Int>()
        for (section in data.utilityTemplateSections) {
            val newId = utilityDao.insertTemplateSection(
                section.copy(id = 0, propertyId = remapPropertyId(section.propertyId)),
            ).toInt()
            if (section.id > 0) templateSectionIdMap[section.id] = newId
        }
        val templateLineIdMap = mutableMapOf<Int, Int>()
        for (line in data.utilityTemplateLines) {
            val sectionId = templateSectionIdMap[line.sectionId] ?: continue
            val newId = utilityDao.insertTemplateLine(line.copy(id = 0, sectionId = sectionId)).toInt()
            if (line.id > 0) templateLineIdMap[line.id] = newId
        }
        for (tariff in data.utilityTariffs) {
            val lineId = templateLineIdMap[tariff.templateLineId] ?: continue
            utilityDao.upsertTariff(tariff.copy(id = 0, templateLineId = lineId))
        }

        if (data.participantNames.isNotEmpty()) {
            ParticipantPreferences.mergeNames(appContext, data.participantNames)
        }

        return ActiveBudgetPreferences.getActiveBudgetId(appContext)
            .takeIf { it in profileIdMap.values }
            ?: fallbackProfileId
    }

    private fun restorePhotoUri(
        photo: UtilityBillPhotoEntity,
        bill: UtilityBillEntity,
        photoBytes: Map<String, ByteArray>,
    ): String? {
        val archivePath = photo.storedUri.takeIf { it.startsWith(BackupArchiveHelper.PHOTOS_PREFIX) } ?: return null
        val bytes = photoBytes[archivePath] ?: return null
        return UtilityPhotoStorage.persistImportedBytes(
            appContext,
            bill,
            photo.photoType,
            photo.sortOrder,
            bytes,
        ) ?: archivePath
    }

    private fun remapParentId(parentId: Int, categoryIdMap: Map<Int, Int>): Int {
        if (parentId <= 0) return parentId
        return categoryIdMap[parentId] ?: parentId
    }

    private fun remapId(
        oldId: Int,
        idMap: Map<Int, Int>,
        validIds: Set<Int>,
        fallbackId: Int,
    ): Int {
        return idMap[oldId]
            ?: oldId.takeIf { it in validIds }
            ?: fallbackId
    }

    private fun normalizeCategory(category: BudgetCategoryEntity): BudgetCategoryEntity {
        val budgetId = category.budgetId.takeIf { it > 0 } ?: ActiveBudgetPreferences.DEFAULT_BUDGET_ID
        return category.copy(
            name = category.name.ifBlank { "Статья ${category.id}" },
            budgetId = budgetId,
        )
    }

    private fun normalizeTransaction(transaction: TransactionEntity): TransactionEntity {
        return transaction.copy(
            id = 0,
            description = transaction.description.ifBlank { "" },
            type = transaction.type.ifBlank { "expense" },
        )
    }

    private fun normalizeReminder(reminder: PaymentReminderEntity): PaymentReminderEntity {
        return reminder.copy(
            id = 0,
            title = reminder.title.ifBlank { "Напоминание" },
            repeatType = reminder.repeatType.ifBlank { "once" },
            dueDate = reminder.dueDate.ifBlank { "1970-01-01" },
        )
    }

    companion object {
        private const val DEFAULT_METER_NAME = "Счётчик"
    }
}
