package ru.mybudget.app

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import ru.mybudget.app.data.*

/** Each test uses its own in-memory database, never the user's budget. */
@RunWith(AndroidJUnit4::class)
class DataIntegrityTest {
    private lateinit var db: BudgetDatabase
    private lateinit var backup: BackupManager
    private val dao get() = db.budgetDao()
    private val auto get() = db.autoDao()

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, BudgetDatabase::class.java).build()
        backup = BackupManager(context, db)
        dao.insertBudgetProfile(BudgetProfileEntity(id = 1, name = "Test"))
        dao.insertCategory(BudgetCategoryEntity(1, "Root", 0, currentBalance = 1000.0))
        dao.insertCategory(BudgetCategoryEntity(2, "Child", 1, currentBalance = 1000.0))
    }

    @After
    fun tearDown() = db.close()

    private suspend fun seedAdditionalData() {
        dao.recordTransaction(2, 100.0, "expense", "car")
        val tx = dao.getAllTransactionsOnce().single().id.toLong()
        val vehicle = auto.insertVehicle(VehicleEntity(name = "Car")).toInt()
        auto.insertServiceLog(VehicleServiceLogEntity(vehicleId = vehicle, dateEpochDay = 20000,
            workDescription = "Service", amount = 100.0, categoryId = 2, paidTransactionId = tx))
        auto.insertInsurance(VehicleInsuranceEntity(vehicleId = vehicle, startDateEpochDay = 20000,
            endDateEpochDay = 20365, amount = 200.0, categoryId = 2, paidTransactionId = tx))
        auto.insertFuelLog(VehicleFuelLogEntity(vehicleId = vehicle, dateEpochDay = 20000,
            liters = 10.0, pricePerLiter = 60.0, amount = 600.0, categoryId = 2, paidTransactionId = tx))
        auto.insertRepair(VehicleRepairEntity(vehicleId = vehicle, dateEpochDay = 20000,
            orderDescription = "Repair", amount = 300.0, categoryId = 2, paidTransactionId = tx))
        val source = dao.insertPlannedIncomeSource(PlannedIncomeSourceEntity(budgetId = 1,
            name = "Salary", amount = 10000.0, sourceType = "salary")).toInt()
        dao.upsertMonthlyIncomePlan(MonthlyIncomePlanEntity(2026, 10, source, 1, 12000.0, false))
        val lines = ForecastSnapshotCodec.LinesDto(
            listOf(ForecastSnapshotCodec.IncomeLineDto(source, "Salary", 12000.0)),
            listOf(ForecastSnapshotCodec.ExpenseLineDto(2, "Child", 100.0, "PLAN")),
        )
        dao.upsertForecastSnapshot(ForecastSnapshotEntity(budgetId = 1, year = 2026, month = 10,
            scenario = "base", totalIncome = 12000.0, totalExpense = 100.0, net = 11900.0,
            linesJson = Gson().toJson(lines)))
    }

    private suspend fun assertAdditionalData() {
        val vehicle = auto.getAllVehicles().single()
        val tx = dao.getAllTransactionsOnce().single()
        val service = auto.getServiceLogs(vehicle.id).single()
        val insurance = auto.getInsurances(vehicle.id).single()
        val fuel = auto.getFuelLogs(vehicle.id).single()
        val repair = auto.getRepairs(vehicle.id).single()
        listOf(service.paidTransactionId, insurance.paidTransactionId, fuel.paidTransactionId,
            repair.paidTransactionId).forEach { assertEquals(tx.id.toLong(), it) }
        listOf(service.categoryId, insurance.categoryId, fuel.categoryId, repair.categoryId)
            .forEach { assertEquals(tx.categoryId.toLong(), it) }
        assertEquals("Service", service.workDescription)
        assertEquals(20365L, insurance.endDateEpochDay)
        assertEquals(10.0, fuel.liters, 0.0)
        assertEquals("Repair", repair.orderDescription)
        val plan = dao.getAllMonthlyIncomePlansForExport().single()
        val source = dao.getAllPlannedIncomeSourcesForExport().single()
        assertEquals(source.id, plan.sourceId)
        assertEquals(source.budgetId, plan.budgetId)
        assertEquals(12000.0, plan.amount, 0.0)
        assertFalse(plan.isEnabled)
        val snapshot = dao.getAllForecastSnapshotsForExport().single()
        assertEquals(plan.budgetId, snapshot.budgetId)
        val lines = requireNotNull(ForecastSnapshotCodec.decode(snapshot.linesJson))
        assertEquals(source.id, lines.incomes.single().sourceId)
        assertEquals(tx.categoryId, lines.expenses.single().categoryId)
        assertEquals(11900.0, snapshot.net, 0.0)
    }

    @Test
    fun replaceBackupRestoresAllNewTablesAndRemapsLinks() = runBlocking {
        seedAdditionalData()
        val oldTx = dao.getAllTransactionsOnce().single().id
        val json = backup.exportToJson()
        auto.insertVehicle(VehicleEntity(name = "Must disappear"))
        val result = backup.importFromJson(json)
        assertTrue(result.message, result.success)
        assertAdditionalData()
        assertNotEquals(oldTx, dao.getAllTransactionsOnce().single().id)
        assertEquals(900.0, dao.getCategoryById(1)!!.currentBalance, 0.0)
        val again = backup.importFromJson(json)
        assertTrue(again.message, again.success)
        assertAdditionalData()
    }

    @Test
    fun encryptedBackupRestoresNewTables() = runBlocking {
        seedAdditionalData()
        val json = backup.exportToJson("test password")
        val result = backup.importFromJson(json, "test password")
        assertTrue(result.message, result.success)
        assertAdditionalData()
    }

    @Test
    fun encryptedArchiveRestoresNewTables() = runBlocking {
        seedAdditionalData()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = java.io.File.createTempFile("integrity-", ".zip", context.cacheDir)
        try {
            val uri = android.net.Uri.fromFile(file)
            assertTrue(backup.exportToArchiveFile(uri, "archive password"))
            val result = backup.importFromFile(uri, "archive password")
            assertTrue(result.message, result.success)
            assertAdditionalData()
        } finally {
            file.delete()
        }
    }

    @Test
    fun mergeRemapsCollidingIdsWithoutReplacingLocalVehicle() = runBlocking {
        seedAdditionalData()
        val json = backup.exportToJson()
        // Start a different database with colliding IDs, as on another device.
        db.clearAllTables()
        dao.insertBudgetProfile(BudgetProfileEntity(id = 1, name = "Other"))
        dao.insertCategory(BudgetCategoryEntity(1, "Unrelated", 0))
        dao.insertCategory(BudgetCategoryEntity(2, "Unrelated child", 1))
        val localVehicle = auto.insertVehicle(VehicleEntity(id = 1, name = "Local car")).toInt()
        val result = backup.importFromJson(json, mode = BackupImportMode.MERGE)
        assertTrue(result.message, result.success)
        assertEquals("Local car", auto.getVehicleById(localVehicle)!!.name)
        auto.deleteVehicle(localVehicle)
        assertAdditionalData()
        assertNotEquals(2, dao.getAllTransactionsOnce().single().categoryId)
        assertNotEquals(1, dao.getAllMonthlyIncomePlansForExport().single().budgetId)
    }

    @Test
    fun legacyBackupWithoutNewFieldsStillImports() = runBlocking {
        seedAdditionalData()
        val json = JsonParser.parseString(backup.exportToJson()).asJsonObject
        listOf("vehicles", "vehicleServiceLogs", "vehicleInsurances", "vehicleFuelLogs",
            "vehicleRepairs", "monthlyIncomePlans", "forecastSnapshots").forEach { json.remove(it) }
        json.addProperty("version", 19)
        val result = backup.importFromJson(json.toString())
        assertTrue(result.message, result.success)
        assertTrue(auto.getAllVehicles().isEmpty())
        assertTrue(dao.getAllMonthlyIncomePlansForExport().isEmpty())
        assertTrue(dao.getAllForecastSnapshotsForExport().isEmpty())
        assertEquals(900.0, dao.getCategoryById(1)!!.currentBalance, 0.0)
    }

    @Test
    fun invalidVehicleReferenceRollsBackEntireImport() = runBlocking {
        seedAdditionalData()
        val before = dao.getAllTransactionsOnce()
        val json = JsonParser.parseString(backup.exportToJson()).asJsonObject
        json.getAsJsonArray("vehicleRepairs")[0].asJsonObject.addProperty("vehicleId", 999)
        assertFalse(backup.importFromJson(json.toString()).success)
        assertEquals(before, dao.getAllTransactionsOnce())
        assertAdditionalData()
    }

    @Test
    fun correctionUsesCurrentTransactionWhenCallerHasOldValue() = runBlocking {
        dao.recordTransaction(2, 100.0, "expense", "original")
        val original = dao.getAllTransactionsOnce().single()
        val repository = BudgetRepository(dao)
        repository.correctTransaction(original, 2, 150.0, "expense", "first")
        repository.correctTransaction(original, 2, 200.0, "expense", "second")
        assertEquals(800.0, dao.getCategoryById(1)!!.currentBalance, 0.0)
        assertEquals(800.0, dao.getCategoryById(2)!!.currentBalance, 0.0)
        assertEquals(200.0, dao.getTransactionById(original.id)!!.amount, 0.0)
    }

    @Test
    fun correctionFailureAfterBalanceUpdatesRollsEverythingBack() = runBlocking {
        dao.recordTransaction(2, 100.0, "expense", "original")
        val original = dao.getAllTransactionsOnce().single()
        val snapshots = dao.getAllBalanceSnapshotsForExport()
        db.openHelper.writableDatabase.execSQL("""
            CREATE TRIGGER reject_transaction_update BEFORE UPDATE ON transactions
            BEGIN SELECT RAISE(ABORT, 'forced test failure'); END
        """.trimIndent())
        val result = runCatching {
            BudgetRepository(dao).correctTransaction(original, 2, 200.0, "expense", "changed")
        }
        assertTrue(result.isFailure)
        assertEquals(900.0, dao.getCategoryById(1)!!.currentBalance, 0.0)
        assertEquals(900.0, dao.getCategoryById(2)!!.currentBalance, 0.0)
        assertEquals(original, dao.getTransactionById(original.id))
        assertEquals(snapshots, dao.getAllBalanceSnapshotsForExport())
    }

    @Test
    fun correctionMovesAmountBetweenCategoriesAndChangesType() = runBlocking {
        dao.insertCategory(BudgetCategoryEntity(3, "Other root", 0, currentBalance = 500.0))
        dao.recordTransaction(2, 100.0, "expense", "original")
        val original = dao.getAllTransactionsOnce().single()
        BudgetRepository(dao).correctTransaction(original, 3, 200.0, "income", "corrected", " Person ")
        assertEquals(1000.0, dao.getCategoryById(1)!!.currentBalance, 0.0)
        assertEquals(1000.0, dao.getCategoryById(2)!!.currentBalance, 0.0)
        assertEquals(700.0, dao.getCategoryById(3)!!.currentBalance, 0.0)
        assertEquals(original.copy(categoryId = 3, amount = 200.0, type = "income",
            description = "corrected", participantLabel = "Person"), dao.getTransactionById(original.id))
    }

    @Test
    fun correctionRejectsMissingCategoryAndDeletedTransaction() = runBlocking {
        dao.recordTransaction(2, 100.0, "expense", "original")
        val original = dao.getAllTransactionsOnce().single()
        val repository = BudgetRepository(dao)
        assertTrue(runCatching {
            repository.correctTransaction(original, 999, 200.0, "expense", "invalid")
        }.isFailure)
        assertEquals(900.0, dao.getCategoryById(1)!!.currentBalance, 0.0)
        repository.cancelTransaction(original)
        assertTrue(runCatching {
            repository.correctTransaction(original, 2, 200.0, "expense", "deleted")
        }.isFailure)
        assertEquals(1000.0, dao.getCategoryById(1)!!.currentBalance, 0.0)
    }
}
