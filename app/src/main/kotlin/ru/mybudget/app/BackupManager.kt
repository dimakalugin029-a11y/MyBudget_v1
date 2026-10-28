package ru.mybudget.app

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonSyntaxException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.mybudget.app.data.AuditActionEntity
import ru.mybudget.app.data.BudgetCategoryEntity
import ru.mybudget.app.data.MonthlyCategoryPlanEntity
import ru.mybudget.app.data.BudgetDatabase
import ru.mybudget.app.data.BudgetRepository
import ru.mybudget.app.data.BudgetProfileEntity
import ru.mybudget.app.data.ObligationPaymentEntity
import ru.mybudget.app.data.PaymentReminderEntity
import ru.mybudget.app.data.PlannedObligationEntity
import ru.mybudget.app.data.RecurringTransactionEntity
import ru.mybudget.app.data.SavingsGoalEntity
import ru.mybudget.app.data.TransactionEntity
import ru.mybudget.app.data.UtilityBillEntity
import ru.mybudget.app.data.UtilityBillPhotoEntity
import ru.mybudget.app.data.UtilityLineItemEntity
import ru.mybudget.app.data.UtilityMeterInfoEntity
import ru.mybudget.app.data.UtilityMeterReadingEntity
import ru.mybudget.app.data.UtilityPropertyEntity
import ru.mybudget.app.data.UtilitySectionEntity
import ru.mybudget.app.data.UtilityTemplateLineEntity
import ru.mybudget.app.data.UtilityTemplateSectionEntity
import ru.mybudget.app.security.BackupCrypto
import ru.mybudget.app.setup.ActiveBudgetPreferences
import ru.mybudget.app.setup.ActivePropertyPreferences
import ru.mybudget.app.setup.ParticipantPreferences
import ru.mybudget.app.utilities.UtilityPhotoStorage
import ru.mybudget.app.utilities.UtilityPropertyCopyHelper
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import kotlin.io.use

class BackupManager internal constructor(context: Context, private val database: BudgetDatabase) {
    constructor(context: Context) : this(context, BudgetDatabase.getInstance(context.applicationContext))

    private val appContext = context.applicationContext
    private val dao = database.budgetDao()
    private val utilityDao = database.utilityDao()
    private val autoDao = database.autoDao()
    var lastExportError: String? = null
        private set
    private val gson: Gson = GsonBuilder().serializeNulls().create()

    suspend fun buildFullBackup(password: String? = null): ByteArray = withContext(Dispatchers.IO) {
        val zip = database.withTransaction {
            val photos = utilityDao.getAllBillPhotosForExport().toMutableList()
            val bills = utilityDao.getAllBillsForExport()
            val properties = utilityDao.getAllPropertiesForExport().associateBy { it.id }
            bills.forEach { bill ->
                val legacyUri = bill.receiptPhotoUri
                if (!legacyUri.isNullOrBlank() && photos.none { it.billId == bill.id && it.photoType == UtilityBillPhotoEntity.TYPE_RECEIPT }) {
                    photos.add(UtilityBillPhotoEntity(billId = bill.id, photoType = UtilityBillPhotoEntity.TYPE_RECEIPT, storedUri = legacyUri))
                }
            }
            val json = buildPlainJson(BackupArchiveHelper.rewritePhotosForArchive(photos))
            val billLabels = bills.associate { bill ->
                bill.id to "${properties[bill.propertyId]?.name ?: "Объект #${bill.propertyId}"}, ${UtilityPhotoStorage.monthFolderLabel(bill.year, bill.month)}"
            }
            BackupArchiveHelper.exportArchive(appContext, json, photos, billLabels)
        }
        // Parse the generated archive before declaring it a complete backup.
        FullBackupContainer.validateAttachments(BackupArchiveHelper.readArchive(zip))
        FullBackupContainer.encode(zip, password)
    }

    suspend fun exportToArchiveFile(uri: Uri, password: String? = null): Boolean = withContext(Dispatchers.IO) {
        lastExportError = null
        try {
            val bytes = buildFullBackup(password)
            appContext.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                ?: error("cannot open output stream")
            appContext.contentResolver.openInputStream(uri)?.use { input ->
                val digest = java.security.MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(8192)
                var total = 0L
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= FullBackupContainer.MAX_FILE_BYTES) { "Копия слишком большая" }
                    digest.update(buffer, 0, count)
                }
                require(java.security.MessageDigest.isEqual(digest.digest(),
                    java.security.MessageDigest.getInstance("SHA-256").digest(bytes))) { "Проверка записи копии не пройдена" }
            } ?: error("cannot verify backup")
            true
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled
        } catch (e: Exception) {
            lastExportError = e.message
            android.util.Log.w("FullBackup", "Export failed", e)
            false
        }
    }

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
                BackupArchiveHelper.readBounded(input, FullBackupContainer.MAX_FILE_BYTES).toString(Charsets.UTF_8)
            }
        }.getOrNull()
    }

    suspend fun importFromJson(
        json: String,
        password: String? = null,
        mode: BackupImportMode = BackupImportMode.REPLACE,
    ): BackupImportResult =
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
            try {
                val full = FullBackupContainer.unpack(plainJson)
                if (full == null) importPlainJson(plainJson, mode) else importPlainJson(full.json, mode, full.photoBytes)
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled
            } catch (e: Exception) {
                BackupImportResult(success = false, message = appContext.getString(R.string.backup_import_failed, e.message.orEmpty()))
            }
        }

    suspend fun importFromFile(
        uri: Uri,
        password: String? = null,
        mode: BackupImportMode = BackupImportMode.REPLACE,
    ): BackupImportResult {
        val archive = readArchiveContent(uri)
        if (archive != null) {
            val payload = archive.json.trim().removePrefix("\uFEFF")
            if (payload.isBlank()) {
                return BackupImportResult(
                    success = false,
                    message = appContext.getString(R.string.backup_import_empty_file),
                )
            }
            validateBackupContent(payload)?.let { return it }
            val plainJson = when {
                BackupCrypto.isEncryptedJson(payload) -> {
                    val pwd = password?.takeIf { it.isNotBlank() }
                        ?: return BackupImportResult(
                            success = false,
                            message = appContext.getString(R.string.backup_import_password_required),
                        )
                    BackupCrypto.decrypt(payload, pwd).getOrElse {
                        return BackupImportResult(
                            success = false,
                            message = appContext.getString(R.string.backup_import_wrong_password),
                        )
                    }
                }
                else -> payload
            }
            try {
                FullBackupContainer.validateAttachments(BackupArchiveHelper.ArchiveContent(plainJson, archive.photoBytes))
            } catch (e: Exception) {
                return BackupImportResult(success = false, message = appContext.getString(R.string.backup_import_failed, e.message.orEmpty()))
            }
            return importPlainJson(plainJson, mode, archive.photoBytes)
        }
        val content = readFileContent(uri)
            ?: return BackupImportResult(
                success = false,
                message = appContext.getString(R.string.backup_import_cannot_read_file),
            )
        return importFromJson(content, password, mode)
    }

    private suspend fun readArchiveContent(uri: Uri): BackupArchiveHelper.ArchiveContent? {
        return withContext(Dispatchers.IO) {
            BackupArchiveHelper.readArchive(appContext, uri)
        }
    }

    private suspend fun buildPlainJson(
        photosForArchive: List<UtilityBillPhotoEntity>? = null,
    ): String = database.withTransaction {
        val photos = photosForArchive ?: utilityDao.getAllBillPhotosForExport()
        val data = BackupData(
            balanceLedgerState = dao.getLedgerState()?.copy(kind="adjustment", operationId=""),
            balanceMovements = dao.getAllBalanceMovements(),
            version = BackupData.CURRENT_VERSION,
            exportedAt = System.currentTimeMillis(),
            budgetProfiles = dao.getAllBudgetProfilesForExport(),
            categories = dao.getAllCategoriesForExport(),
            transactions = dao.getAllTransactionsOnce(),
            reminders = dao.getAllRemindersForExport(),
            savingsGoals = dao.getAllSavingsGoalsForExport(),
            recurringTransactions = dao.getAllRecurringForExport(),
            plannedObligations = dao.getAllPlannedObligationsForExport().map {
                it.copy(attentionStartMonth = PaymentTracking.startMonth(it, java.time.LocalDate.now()).toString())
            },
            obligationPayments = dao.getAllObligationPaymentsForExport(),
            balanceSnapshots = dao.getAllBalanceSnapshotsForExport(),
            plannedIncomeSources = dao.getAllPlannedIncomeSourcesForExport(),
            utilityProperties = utilityDao.getAllPropertiesForExport(),
            utilityBills = utilityDao.getAllBillsForExport(),
            utilityBillPhotos = photos,
            utilitySections = utilityDao.getAllSectionsForExport(),
            utilityLineItems = utilityDao.getAllLineItemsForExport(),
            utilityMeterReadings = utilityDao.getAllMeterReadingsForExport(),
            utilityMeterInfo = utilityDao.getAllMeterInfoForExport(),
            utilityTemplateSections = utilityDao.getAllTemplateSectionsForExport(),
            utilityTemplateLines = utilityDao.getAllTemplateLinesForExport(),
            utilityTariffs = utilityDao.getAllTariffsForExport(),
            monthlyCategoryPlans = dao.getAllMonthlyPlansForExport(),
            auditActions = dao.getAllAuditActionsForExport(),
            participantNames = ParticipantPreferences.getNames(appContext),
            monthlyIncomePlans = dao.getAllMonthlyIncomePlansForExport(),
            forecastSnapshots = dao.getAllForecastSnapshotsForExport(),
            vehicles = autoDao.getAllVehicles(),
            vehicleServiceLogs = autoDao.getAllServiceLogsForExport(),
            vehicleInsurances = autoDao.getAllInsurances(),
            vehicleFuelLogs = autoDao.getAllFuelLogsForExport(),
            vehicleRepairs = autoDao.getAllRepairsForExport(),
        )
        gson.toJson(data)
    }

    private suspend fun importPlainJson(
        cleanJson: String,
        mode: BackupImportMode,
        photoBytes: Map<String, ByteArray> = emptyMap(),
    ): BackupImportResult {
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
            val activeId = database.withTransaction {
                when (mode) {
                    BackupImportMode.REPLACE -> importData(data, photoBytes)
                    BackupImportMode.MERGE -> {
                        val active = BackupMergeImporter(appContext, dao, utilityDao, autoDao).import(data, photoBytes)
                        dao.resetBalanceLedger()
                        active
                    }
                }
            }
            if (mode == BackupImportMode.REPLACE && data.participantNames.isNotEmpty()) {
                ParticipantPreferences.setNames(appContext, data.participantNames)
            }
            ActiveBudgetPreferences.setActiveBudgetId(appContext, activeId)
            BackupImportResult(
                success = true,
                message = appContext.getString(R.string.backup_import_success_version, data.version) +
                    if (mode==BackupImportMode.MERGE || data.balanceLedgerState==null) "\n" + appContext.getString(R.string.ledger_import_restart) else "",
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

    private suspend fun importData(
        data: BackupData,
        photoBytes: Map<String, ByteArray> = emptyMap(),
    ): Int {
        autoDao.deleteAllVehicles()
        dao.deleteAllMonthlyIncomePlans()
        dao.deleteAllForecastSnapshots()
        dao.deleteAllTransactions()
        dao.deleteAllReminders()
        dao.deleteAllSavingsGoals()
        dao.deleteAllRecurring()
        dao.deleteAllObligationPayments()
        dao.deleteAllBalanceSnapshots()
        dao.deleteAllPlannedObligations()
        dao.deleteAllPlannedIncomeSources()
        dao.deleteAllCategories()
        dao.deleteAllBudgetProfiles()
        dao.deleteAllMonthlyPlans()
        dao.deleteAllAuditActions()

        utilityDao.deleteAllBillPhotos()
        utilityDao.deleteAllMeterInfo()
        utilityDao.deleteAllMeterReadings()
        utilityDao.deleteAllTariffs()
        utilityDao.deleteAllTemplateLines()
        utilityDao.deleteAllTemplateSections()
        utilityDao.deleteAllLineItems()
        utilityDao.deleteAllSections()
        utilityDao.deleteAllBills()
        utilityDao.deleteAllProperties()

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
        val transactionIdMap = mutableMapOf<Long, Long>()
        for (transaction in data.transactions) {
            if (transaction.categoryId in insertedCategoryIds) {
                transactionIdMap[transaction.id.toLong()] = dao.insertTransaction(normalizeTransaction(transaction))
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
        BackupAdditionalDataImporter.import(
            data, dao, autoDao, profileIdMap, fallbackProfileId,
            insertedCategoryIds.associateWith { it }, transactionIdMap, incomeSourceIdMap,
        )
        for (obligation in data.plannedObligations) {
            val budgetId = remapId(obligation.budgetId, profileIdMap, validProfileIds, fallbackProfileId)
            val sourceId = obligation.id
            val linkedIncomeSourceId = obligation.linkedIncomeSourceId?.let { oldId ->
                incomeSourceIdMap[oldId]
            }
            val newId = dao.insertPlannedObligation(
                obligation.copy(
                    id = 0,
                    budgetId = budgetId,
                    obligationKind = PlannedObligationHelper.normalizeKind(obligation.obligationKind),
                    linkedIncomeSourceId = linkedIncomeSourceId,
                    attentionStartMonth = PaymentTracking.importedStartMonth(obligation.attentionStartMonth),
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
            dao.insertObligationPayment(payment.copy(id = 0, obligationId = obligationId, transactionId = payment.transactionId?.let { transactionIdMap[it.toLong()]?.toInt() }))
        }
        for (snapshot in data.balanceSnapshots) {
            val budgetId = remapId(snapshot.budgetId, profileIdMap, validProfileIds, fallbackProfileId)
            dao.upsertBalanceSnapshot(snapshot.copy(id = 0, budgetId = budgetId))
        }
        for (reminder in data.reminders) {
            if (reminder.categoryId in insertedCategoryIds) {
                dao.insertReminder(
                    normalizeReminder(reminder).copy(
                        obligationId = remapObligationId(reminder.obligationId),
                    ),
                )
            }
        }
        for (goal in data.savingsGoals) {
            if (goal.categoryId in insertedCategoryIds) {
                dao.insertSavingsGoal(goal.copy(id = 0))
            }
        }
        for (recurring in data.recurringTransactions) {
            if (recurring.categoryId in insertedCategoryIds) {
                dao.insertRecurring(
                    recurring.copy(
                        id = 0,
                        obligationId = remapObligationId(recurring.obligationId),
                    ),
                )
            }
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

        val propertyIdMap = mutableMapOf<Int, Int>()
        if (data.utilityProperties.isNotEmpty()) {
            for (property in data.utilityProperties) {
                val newId = utilityDao.insertProperty(property.copy(id = 0)).toInt()
                if (property.id > 0) propertyIdMap[property.id] = newId
                propertyIdMap[newId] = newId
            }
        } else {
            val defaultId = UtilityPropertyCopyHelper.ensureDefaultProperty(utilityDao)
            propertyIdMap[ActivePropertyPreferences.DEFAULT_PROPERTY_ID] = defaultId
            propertyIdMap[defaultId] = defaultId
        }
        fun remapPropertyId(sourceId: Int): Int {
            return propertyIdMap[sourceId]
                ?: propertyIdMap.values.firstOrNull()
                ?: ActivePropertyPreferences.DEFAULT_PROPERTY_ID
        }

        val billIdMap = mutableMapOf<Int, Int>()
        val insertedBillIds = mutableListOf<Int>()
        for (bill in data.utilityBills) {
            val newId = utilityDao.insertBill(
                bill.copy(id = 0, propertyId = remapPropertyId(bill.propertyId)),
            ).toInt()
            insertedBillIds.add(newId)
            if (bill.id > 0) billIdMap[bill.id] = newId
            if (!bill.receiptPhotoUri.isNullOrBlank() &&
                data.utilityBillPhotos.none { it.billId == bill.id }
            ) {
                utilityDao.insertBillPhoto(
                    UtilityBillPhotoEntity(
                        billId = newId,
                        photoType = UtilityBillPhotoEntity.TYPE_RECEIPT,
                        storedUri = bill.receiptPhotoUri,
                        sortOrder = 0,
                    ),
                )
            }
        }
        val singleBillId = insertedBillIds.singleOrNull()

        for (photo in data.utilityBillPhotos) {
            val billId = billIdMap[photo.billId]
                ?: singleBillId
                ?: photo.billId.takeIf { insertedBillIds.contains(it) }
            if (billId != null && billId > 0) {
                val bill = utilityDao.getBillById(billId)
                val storedUri = if (bill != null) {
                    restorePhotoUri(photo, bill, photoBytes) ?: photo.storedUri
                } else {
                    photo.storedUri
                }
                utilityDao.insertBillPhoto(photo.copy(id = 0, billId = billId, storedUri = storedUri))
            }
        }

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
            utilityDao.insertMeterReading(
                reading.copy(
                    id = 0,
                    propertyId = remapPropertyId(reading.propertyId),
                    meterName = meterName,
                ),
            )
        }
        for (info in data.utilityMeterInfo) {
            val meterName = info.meterName.ifBlank { DEFAULT_METER_NAME }
            utilityDao.insertMeterInfo(
                info.copy(
                    id = 0,
                    propertyId = remapPropertyId(info.propertyId),
                    meterName = meterName,
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

        val repository = BudgetRepository(dao)
        dao.getAllPlannedObligationsForExport()
            .filter { it.isActive && (it.remindEnabled || it.autoPostEnabled) }
            .forEach { ObligationLinkedSync.sync(repository, it) }

        val historicalState = data.balanceLedgerState
        if (historicalState == null) {
            dao.resetBalanceLedger()
        } else {
            require(historicalState.startedAt > 0 && historicalState.startedAt <= System.currentTimeMillis()) { "Некорректная граница истории остатков" }
            val rows = data.balanceMovements.map { row ->
                require(row.delta.isFinite() && row.budgetDelta.isFinite() && row.createdAt >= historicalState.startedAt && row.createdAt <= System.currentTimeMillis()) { "Некорректное движение остатка" }
                row.copy(id=0, budgetId=profileIdMap[row.budgetId] ?: -kotlin.math.abs(row.budgetId))
            }
            val balances = dao.getAllCategoriesForExport().filter { it.parentId==0 && it.isActive }.groupBy { it.budgetId }.mapValues { (_,cats)->cats.sumOf { it.currentBalance } }
            val totals = rows.groupBy { it.budgetId }.mapValues { (_,moves)->moves.sumOf { it.budgetDelta } }
            require((balances.keys + totals.keys).all { kotlin.math.abs((balances[it] ?: 0.0)-(totals[it] ?: 0.0)) < 0.01 }) { "История движений не совпадает с остатками копии" }
            dao.deleteBalanceMovements()
            dao.setLedgerState(historicalState.copy(id=1, kind="adjustment", operationId=""))
            dao.insertBalanceMovements(rows)
        }

        val preferredOldId = data.budgetProfiles.firstOrNull { it.id > 0 }?.id
        return preferredOldId?.let { profileIdMap[it] } ?: fallbackProfileId
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
        ) ?: error("Не удалось восстановить фотографию: $archivePath")
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
        balanceLedgerState = balanceLedgerState,
        balanceMovements = balanceMovements.orEmpty(),
        version = version ?: 1,
        exportedAt = exportedAt ?: System.currentTimeMillis(),
        budgetProfiles = budgetProfiles.orEmpty(),
        categories = categories.orEmpty(),
        transactions = transactions.orEmpty(),
        reminders = reminders.orEmpty(),
        savingsGoals = savingsGoals.orEmpty(),
        recurringTransactions = recurringTransactions.orEmpty(),
        plannedObligations = plannedObligations.orEmpty(),
        obligationPayments = obligationPayments.orEmpty(),
        balanceSnapshots = balanceSnapshots.orEmpty(),
        plannedIncomeSources = plannedIncomeSources.orEmpty(),
        utilityProperties = utilityProperties.orEmpty(),
        utilityBills = utilityBills.orEmpty(),
        utilityBillPhotos = utilityBillPhotos.orEmpty(),
        utilitySections = utilitySections.orEmpty(),
        utilityLineItems = utilityLineItems.orEmpty(),
        utilityMeterReadings = utilityMeterReadings.orEmpty(),
        utilityMeterInfo = utilityMeterInfo.orEmpty(),
        utilityTemplateSections = utilityTemplateSections.orEmpty(),
        utilityTemplateLines = utilityTemplateLines.orEmpty(),
        utilityTariffs = utilityTariffs.orEmpty(),
        monthlyCategoryPlans = monthlyCategoryPlans.orEmpty(),
        auditActions = auditActions.orEmpty(),
        participantNames = participantNames.orEmpty(),
        monthlyIncomePlans = monthlyIncomePlans.orEmpty(),
        forecastSnapshots = forecastSnapshots.orEmpty(),
        vehicles = vehicles.orEmpty(),
        vehicleServiceLogs = vehicleServiceLogs.orEmpty(),
        vehicleInsurances = vehicleInsurances.orEmpty(),
        vehicleFuelLogs = vehicleFuelLogs.orEmpty(),
        vehicleRepairs = vehicleRepairs.orEmpty(),
    )

    private fun validateBackupContent(content: String): BackupImportResult? {
        if (content.startsWith("PK\u0003\u0004") || content.startsWith("PK")) {
            return BackupImportResult(
                success = false,
                message = appContext.getString(R.string.backup_import_use_archive_flow),
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
