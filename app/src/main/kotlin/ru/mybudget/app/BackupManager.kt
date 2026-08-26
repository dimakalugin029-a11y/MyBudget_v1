package ru.mybudget.app

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonSyntaxException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import ru.mybudget.app.data.AuditActionEntity
import ru.mybudget.app.data.BudgetCategoryEntity
import ru.mybudget.app.data.MonthlyCategoryPlanEntity
import ru.mybudget.app.data.BudgetDatabase
import ru.mybudget.app.data.BudgetProfileEntity
import ru.mybudget.app.data.PaymentReminderEntity
import ru.mybudget.app.data.PlannedObligationEntity
import ru.mybudget.app.data.RecurringTransactionEntity
import ru.mybudget.app.data.SavingsGoalEntity
import ru.mybudget.app.data.TransactionEntity
import ru.mybudget.app.data.UtilityBillEntity
import ru.mybudget.app.data.UtilityLineItemEntity
import ru.mybudget.app.data.UtilityMeterInfoEntity
import ru.mybudget.app.data.UtilityMeterReadingEntity
import ru.mybudget.app.data.UtilitySectionEntity
import ru.mybudget.app.data.UtilityTemplateLineEntity
import ru.mybudget.app.data.UtilityTemplateSectionEntity
import ru.mybudget.app.security.BackupCrypto
import ru.mybudget.app.setup.ActiveBudgetPreferences
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import kotlin.io.use

class BackupManager(context: Context) {
    private val appContext = context.applicationContext
    private val database = BudgetDatabase.getInstance(appContext)
    private val dao = database.budgetDao()
    private val utilityDao = database.utilityDao()
    private val gson: Gson = GsonBuilder().serializeNulls().create()

    suspend fun exportToJson(password: String? = null): String = withContext(Dispatchers.IO) {
        val plainJson = buildPlainJson()
        if (password.isNullOrBlank()) plainJson else BackupCrypto.encrypt(plainJson, password)
    }

    suspend fun exportToFile(uri: Uri, password: String? = null): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            appContext.contentResolver.openOutputStream(uri)?.use { os ->
                OutputStreamWriter(os, Charsets.UTF_8).use { writer ->
                    writer.write(exportToJson(password))
                }
            } ?: error("cannot open output stream")
        }.isSuccess
    }

    suspend fun readFileContent(uri: Uri): String? = withContext(Dispatchers.IO) {
        runCatching {
            appContext.contentResolver.openInputStream(uri)?.use { input ->
                BufferedReader(InputStreamReader(input, Charsets.UTF_8)).readText()
            }
        }.getOrNull()
    }

    suspend fun importFromJson(json: String, password: String? = null): BackupImportResult =
        withContext(Dispatchers.IO) {
            val cleanJson = json.trim().removePrefix("\uFEFF")
            if (cleanJson.isBlank()) {
                return@withContext BackupImportResult(
                    success = false,
                    message = appContext.getString(R.string.backup_import_empty_file),
                )
            }
            validateBackupContent(cleanJson)?.let { return@withContext it }
            val plainJson = when {
                BackupCrypto.isEncryptedJson(cleanJson) -> {
                    val pwd = password?.takeIf { it.isNotBlank() }
                        ?: return@withContext BackupImportResult(
                            success = false,
                            message = appContext.getString(R.string.backup_import_password_required),
                        )
                    BackupCrypto.decrypt(cleanJson, pwd).getOrElse {
                        return@withContext BackupImportResult(
                            success = false,
                            message = appContext.getString(R.string.backup_import_wrong_password),
                        )
                    }
                }
                else -> cleanJson
            }
            importPlainJson(plainJson)
        }

    suspend fun importFromFile(uri: Uri, password: String? = null): BackupImportResult {
        val content = readFileContent(uri)
            ?: return BackupImportResult(
                success = false,
                message = appContext.getString(R.string.backup_import_cannot_read_file),
            )
        return importFromJson(content, password)
    }

    private suspend fun buildPlainJson(): String {
        val data = BackupData(
            version = BackupData.CURRENT_VERSION,
            exportedAt = System.currentTimeMillis(),
            budgetProfiles = dao.getAllBudgetProfilesForExport(),
            categories = dao.getAllCategoriesForExport(),
            transactions = dao.getAllTransactions().first(),
            reminders = dao.getAllRemindersForExport(),
            savingsGoals = dao.getAllSavingsGoalsForExport(),
            recurringTransactions = dao.getAllRecurringForExport(),
            plannedObligations = dao.getAllPlannedObligationsForExport(),
            utilityBills = utilityDao.getAllBillsForExport(),
            utilitySections = utilityDao.getAllSectionsForExport(),
            utilityLineItems = utilityDao.getAllLineItemsForExport(),
            utilityMeterReadings = utilityDao.getAllMeterReadingsForExport(),
            utilityMeterInfo = utilityDao.getAllMeterInfoForExport(),
            utilityTemplateSections = utilityDao.getAllTemplateSectionsForExport(),
            utilityTemplateLines = utilityDao.getAllTemplateLinesForExport(),
            utilityTariffs = utilityDao.getAllTariffsForExport(),
            monthlyCategoryPlans = dao.getAllMonthlyPlansForExport(),
            auditActions = dao.getAllAuditActionsForExport(),
        )
        return gson.toJson(data)
    }

    private suspend fun importPlainJson(cleanJson: String): BackupImportResult {
        return try {
            val dto = gson.fromJson(cleanJson, BackupDataDto::class.java)
                ?: return BackupImportResult(
                    success = false,
                    message = appContext.getString(R.string.backup_import_invalid_json, ""),
                )
            val data = dto.toBackupData()
            if (data.categories.isEmpty()) {
                return BackupImportResult(
                    success = false,
                    message = appContext.getString(R.string.backup_import_no_categories),
                )
            }
            val activeId = database.withTransaction { importData(data) }
            ActiveBudgetPreferences.setActiveBudgetId(appContext, activeId)
            BackupImportResult(
                success = true,
                message = appContext.getString(R.string.backup_import_success_version, data.version),
                backupVersion = data.version,
            )
        } catch (e: JsonSyntaxException) {
            val message = if (looksTruncated(cleanJson)) {
                appContext.getString(R.string.backup_import_truncated)
            } else {
                appContext.getString(R.string.backup_import_invalid_json, e.message ?: "")
            }
            BackupImportResult(
                success = false,
                message = message,
            )
        } catch (e: Exception) {
            e.printStackTrace()
            BackupImportResult(
                success = false,
                message = appContext.getString(
                    R.string.backup_import_failed,
                    e.message ?: e.javaClass.simpleName,
                ),
            )
        }
    }

    private suspend fun importData(data: BackupData): Int {
        dao.deleteAllTransactions()
        dao.deleteAllReminders()
        dao.deleteAllSavingsGoals()
        dao.deleteAllRecurring()
        dao.deleteAllPlannedObligations()
        dao.deleteAllCategories()
        dao.deleteAllBudgetProfiles()
        dao.deleteAllMonthlyPlans()
        dao.deleteAllAuditActions()

        utilityDao.deleteAllMeterInfo()
        utilityDao.deleteAllMeterReadings()
        utilityDao.deleteAllTariffs()
        utilityDao.deleteAllTemplateLines()
        utilityDao.deleteAllTemplateSections()
        utilityDao.deleteAllLineItems()
        utilityDao.deleteAllSections()
        utilityDao.deleteAllBills()

        val defaultBudgetName = appContext.getString(R.string.budget_profiles_default_name)
        val profiles = data.budgetProfiles.ifEmpty {
            listOf(BudgetProfileEntity(name = defaultBudgetName))
        }
        val profileIdMap = mutableMapOf<Int, Int>()
        for (profile in profiles) {
            val name = profile.name.ifBlank { defaultBudgetName }
            val toInsert = profile.copy(
                id = profile.id.coerceAtLeast(0),
                name = name,
                isActive = true,
            )
            val insertedId = dao.insertBudgetProfile(toInsert).toInt()
            val newId = when {
                insertedId > 0 -> insertedId
                profile.id > 0 -> profile.id
                else -> continue
            }
            if (profile.id > 0) profileIdMap[profile.id] = newId
            profileIdMap[newId] = newId
        }
        val fallbackProfileId = profileIdMap.values.firstOrNull()
            ?: ActiveBudgetPreferences.DEFAULT_BUDGET_ID
        val validProfileIds = profileIdMap.values.toSet()

        val insertedCategoryIds = mutableSetOf<Int>()
        for (category in data.categories) {
            val budgetId = remapId(category.budgetId, profileIdMap, validProfileIds, fallbackProfileId)
            val normalized = normalizeCategory(category).copy(budgetId = budgetId)
            dao.insertCategory(normalized)
            insertedCategoryIds.add(normalized.id)
        }
        for (transaction in data.transactions) {
            if (transaction.categoryId in insertedCategoryIds) {
                dao.insertTransaction(normalizeTransaction(transaction))
            }
        }
        for (reminder in data.reminders) {
            if (reminder.categoryId in insertedCategoryIds) {
                dao.insertReminder(normalizeReminder(reminder))
            }
        }
        for (goal in data.savingsGoals) {
            if (goal.categoryId in insertedCategoryIds) {
                dao.insertSavingsGoal(goal.copy(id = 0))
            }
        }
        for (recurring in data.recurringTransactions) {
            if (recurring.categoryId in insertedCategoryIds) {
                dao.insertRecurring(recurring.copy(id = 0))
            }
        }
        for (obligation in data.plannedObligations) {
            val budgetId = remapId(obligation.budgetId, profileIdMap, validProfileIds, fallbackProfileId)
            dao.insertPlannedObligation(obligation.copy(id = 0, budgetId = budgetId))
        }
        for (plan in data.monthlyCategoryPlans) {
            if (plan.categoryId in insertedCategoryIds) {
                val budgetId = remapId(plan.budgetId, profileIdMap, validProfileIds, fallbackProfileId)
                dao.upsertMonthlyPlan(
                    MonthlyCategoryPlanEntity(
                        year = plan.year,
                        month = plan.month,
                        categoryId = plan.categoryId,
                        budgetId = budgetId,
                        plannedAmount = plan.plannedAmount,
                        isEnabled = plan.isEnabled,
                    ),
                )
            }
        }
        for (action in data.auditActions) {
            dao.insertAuditAction(
                AuditActionEntity(
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

        val billIdMap = mutableMapOf<Int, Int>()
        val insertedBillIds = mutableListOf<Int>()
        for (bill in data.utilityBills) {
            val newId = utilityDao.insertBill(bill.copy(id = 0)).toInt()
            insertedBillIds.add(newId)
            if (bill.id > 0) billIdMap[bill.id] = newId
        }
        val singleBillId = insertedBillIds.singleOrNull()

        val sectionIdMap = mutableMapOf<Int, Int>()
        for (section in data.utilitySections) {
            val billId = billIdMap[section.billId]
                ?: singleBillId
                ?: section.billId.takeIf { insertedBillIds.contains(it) }
            if (billId != null && billId > 0) {
                val newId = utilityDao.insertSection(section.copy(id = 0, billId = billId)).toInt()
                if (section.id > 0) sectionIdMap[section.id] = newId
            }
        }
        val singleSectionId = sectionIdMap.values.singleOrNull()

        for (line in data.utilityLineItems) {
            val sectionId = sectionIdMap[line.sectionId]
                ?: singleSectionId
                ?: line.sectionId.takeIf { sectionIdMap.values.contains(it) }
            if (sectionId != null && sectionId > 0) {
                utilityDao.insertLineItem(line.copy(id = 0, sectionId = sectionId))
            }
        }

        for (reading in data.utilityMeterReadings) {
            val meterName = reading.meterName.ifBlank { DEFAULT_METER_NAME }
            utilityDao.insertMeterReading(reading.copy(id = 0, meterName = meterName))
        }
        for (info in data.utilityMeterInfo) {
            val meterName = info.meterName.ifBlank { DEFAULT_METER_NAME }
            utilityDao.insertMeterInfo(info.copy(id = 0, meterName = meterName))
        }

        val templateSectionIdMap = mutableMapOf<Int, Int>()
        for (section in data.utilityTemplateSections) {
            val newId = utilityDao.insertTemplateSection(section.copy(id = 0)).toInt()
            if (section.id > 0) templateSectionIdMap[section.id] = newId
        }
        val singleTemplateSectionId = templateSectionIdMap.values.singleOrNull()

        val templateLineIdMap = mutableMapOf<Int, Int>()
        for (line in data.utilityTemplateLines) {
            val sectionId = templateSectionIdMap[line.sectionId]
                ?: singleTemplateSectionId
                ?: line.sectionId.takeIf { templateSectionIdMap.values.contains(it) }
            if (sectionId != null && sectionId > 0) {
                val newId = utilityDao.insertTemplateLine(line.copy(id = 0, sectionId = sectionId)).toInt()
                if (line.id > 0) templateLineIdMap[line.id] = newId
            }
        }

        for (tariff in data.utilityTariffs) {
            val lineId = templateLineIdMap[tariff.templateLineId]
                ?: tariff.templateLineId.takeIf { templateLineIdMap.values.contains(it) }
            if (lineId != null && lineId > 0) {
                utilityDao.upsertTariff(tariff.copy(id = 0, templateLineId = lineId))
            }
        }

        val preferredOldId = data.budgetProfiles.firstOrNull { it.id > 0 }?.id
        return preferredOldId?.let { profileIdMap[it] } ?: fallbackProfileId
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
        return try {
            val budgetId = category.budgetId.takeIf { it > 0 } ?: ActiveBudgetPreferences.DEFAULT_BUDGET_ID
            val name = category.name.ifBlank { "Статья ${category.id}" }
            val colorHex = category.colorHex.ifBlank { "" }
            category.copy(
                name = name,
                parentId = category.parentId,
                budgetId = budgetId,
                colorHex = colorHex,
            )
        } catch (_: Exception) {
            BudgetCategoryEntity(
                id = category.id,
                name = "Статья ${category.id}",
                parentId = category.parentId,
                budgetId = ActiveBudgetPreferences.DEFAULT_BUDGET_ID,
                isActive = false,
            )
        }
    }

    private fun normalizeTransaction(transaction: TransactionEntity): TransactionEntity {
        return try {
            transaction.copy(
                id = 0,
                description = transaction.description.ifBlank { "" },
                type = transaction.type.ifBlank { "expense" },
            )
        } catch (_: Exception) {
            TransactionEntity(
                id = 0,
                categoryId = transaction.categoryId,
                amount = transaction.amount,
                type = "expense",
                description = "",
                date = transaction.date,
            )
        }
    }

    private fun normalizeReminder(reminder: PaymentReminderEntity): PaymentReminderEntity {
        return try {
            reminder.copy(
                id = 0,
                title = reminder.title.ifBlank { "Напоминание" },
                repeatType = reminder.repeatType.ifBlank { "once" },
                dueDate = reminder.dueDate.ifBlank { "1970-01-01" },
            )
        } catch (_: Exception) {
            PaymentReminderEntity(
                id = 0,
                title = "Напоминание",
                amount = reminder.amount,
                categoryId = reminder.categoryId,
                dueDate = "1970-01-01",
                repeatType = "once",
            )
        }
    }

    private fun BackupDataDto.toBackupData(): BackupData = BackupData(
        version = version ?: 1,
        exportedAt = exportedAt ?: System.currentTimeMillis(),
        budgetProfiles = budgetProfiles.orEmpty(),
        categories = categories.orEmpty(),
        transactions = transactions.orEmpty(),
        reminders = reminders.orEmpty(),
        savingsGoals = savingsGoals.orEmpty(),
        recurringTransactions = recurringTransactions.orEmpty(),
        plannedObligations = plannedObligations.orEmpty(),
        utilityBills = utilityBills.orEmpty(),
        utilitySections = utilitySections.orEmpty(),
        utilityLineItems = utilityLineItems.orEmpty(),
        utilityMeterReadings = utilityMeterReadings.orEmpty(),
        utilityMeterInfo = utilityMeterInfo.orEmpty(),
        utilityTemplateSections = utilityTemplateSections.orEmpty(),
        utilityTemplateLines = utilityTemplateLines.orEmpty(),
        utilityTariffs = utilityTariffs.orEmpty(),
        monthlyCategoryPlans = monthlyCategoryPlans.orEmpty(),
        auditActions = auditActions.orEmpty(),
    )

    private fun validateBackupContent(content: String): BackupImportResult? {
        if (content.startsWith("PK\u0003\u0004") || content.startsWith("PK")) {
            return BackupImportResult(
                success = false,
                message = appContext.getString(R.string.backup_import_wrong_format_xlsx),
            )
        }
        if (content.length < 50) {
            return BackupImportResult(
                success = false,
                message = appContext.getString(R.string.backup_import_file_too_small),
            )
        }
        if (!content.startsWith("{")) {
            return BackupImportResult(
                success = false,
                message = appContext.getString(R.string.backup_import_not_json),
            )
        }
        return null
    }

    private fun looksTruncated(content: String): Boolean {
        val trimmed = content.trimEnd()
        return content.length < 500 || !trimmed.endsWith("}")
    }

    companion object {
        private const val DEFAULT_METER_NAME = "Счётчик"
    }
}
