package ru.mybudget.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import ru.mybudget.app.data.BudgetDatabase

/** Builds real historical schemas, seeds related money rows, then lets Room validate the complete upgrade. */
@RunWith(AndroidJUnit4::class)
class DatabaseMigrationChainTest {
    @Test fun version41UpgradesToCurrentWithoutLosingPayments() = verifyUpgrade(41)
    @Test fun version45UpgradesToCurrentWithoutLosingPayments() = verifyUpgrade(45)
    @Test fun version46UpgradesToCurrentWithoutLosingPayments() = verifyUpgrade(46)

    @Test fun version47UpgradesToCurrentWithoutLosingPayments() = verifyUpgrade(47)

    @Test fun version48UpgradesToCurrentWithoutLosingPayments() = verifyUpgrade(48)

    @Test fun version49UpgradesToCurrentWithoutLosingPayments() = verifyUpgrade(49)

    @Test fun freshDatabaseOpensWithCurrentSchema() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, BudgetDatabase::class.java).addCallback(ru.mybudget.app.data.BalanceLedgerSchema.callback).build()
        try {
            assertEquals(50, db.openHelper.writableDatabase.version)
            db.openHelper.writableDatabase.query("PRAGMA integrity_check").use { it.moveToFirst(); assertEquals("ok", it.getString(0)) }
        } finally { db.close() }
    }

    private fun schema(version: Int): JsonObject = InstrumentationRegistry.getInstrumentation().context.assets
        .open("ru.mybudget.app.data.BudgetDatabase/$version.json").bufferedReader().use {
            JsonParser.parseReader(it).asJsonObject.getAsJsonObject("database")
        }

    private fun verifyUpgrade(version: Int) = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "migration-chain-$version-${java.util.UUID.randomUUID()}.db"
        val old = schema(version)
        val helper = FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(name).callback(object : SupportSQLiteOpenHelper.Callback(version) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    old.getAsJsonArray("entities").forEach { item ->
                        val entity = item.asJsonObject
                        val table = entity.get("tableName").asString
                        db.execSQL(entity.get("createSql").asString.replace("\${TABLE_NAME}", table))
                        entity.getAsJsonArray("indices")?.forEach { index ->
                            db.execSQL(index.asJsonObject.get("createSql").asString.replace("\${TABLE_NAME}", table))
                        }
                    }
                    old.getAsJsonArray("views")?.forEach { item ->
                        val view = item.asJsonObject
                        db.execSQL(view.get("createSql").asString.replace("\${VIEW_NAME}", view.get("viewName").asString))
                    }
                    old.getAsJsonArray("setupQueries").forEach { db.execSQL(it.asString) }
                }
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = error("Unexpected fixture upgrade")
            }).build())
        try {
            val sqlite = helper.writableDatabase
            fun seed(table: String, values: Map<String, Any>) {
                val entity = old.getAsJsonArray("entities").map { it.asJsonObject }.single { it.get("tableName").asString == table }
                val row = ContentValues()
                entity.getAsJsonArray("fields").forEach { value ->
                    val field = value.asJsonObject
                    val column = field.get("columnName").asString
                    val provided = values[column]
                    when (provided) {
                        is String -> row.put(column, provided)
                        is Int -> row.put(column, provided)
                        is Long -> row.put(column, provided)
                        is Double -> row.put(column, provided)
                        null -> if (field.get("notNull")?.asBoolean != true) row.putNull(column)
                            else if (field.get("affinity").asString == "TEXT") row.put(column, "") else row.put(column, 0)
                        else -> error("Unsupported fixture value")
                    }
                }
                sqlite.insert(table, SQLiteDatabase.CONFLICT_ABORT, row)
            }
            seed("budget_profiles", mapOf("id" to 1, "name" to "Budget", "isActive" to 1))
            seed("categories", mapOf("id" to 1, "name" to "Parent", "parentId" to 0, "budgetId" to 1, "isActive" to 1, "currentBalance" to 900.0))
            seed("categories", mapOf("id" to 2, "name" to "Car", "parentId" to 1, "budgetId" to 1, "isActive" to 1, "currentBalance" to 900.0))
            seed("planned_income_sources", mapOf("id" to 1, "budgetId" to 1, "name" to "Salary", "amount" to 1000.0, "sourceType" to "salary", "periodType" to "monthly", "isActive" to 1))
            seed("transactions", mapOf("id" to 1, "categoryId" to 2, "amount" to 100.0, "type" to "expense", "description" to "Repair"))
            seed("transactions", mapOf("id" to 2, "categoryId" to 2, "amount" to 200.0, "type" to "income", "description" to "Salary", "plannedIncomeSourceId" to 1, "plannedIncomeMonth" to "2026-10"))
            seed("vehicles", mapOf("id" to 1, "name" to "Car"))
            seed("vehicle_repairs", mapOf("id" to 1, "vehicleId" to 1, "dateEpochDay" to 20000L, "amount" to 100.0, "categoryId" to 2, "paidTransactionId" to 1L, "orderDescription" to "Repair", "paymentMethod" to "budget"))
            seed("planned_obligations", mapOf("id" to 1, "budgetId" to 1, "categoryId" to 2, "name" to "Payment", "amount" to 100.0, "periodType" to "monthly", "isActive" to 1, "attentionStartMonth" to "2026-10"))
            seed("obligation_payments", mapOf("id" to 1, "obligationId" to 1, "periodYear" to 2026, "periodMonth" to 10, "amount" to 100.0, "transactionId" to 1, "dueDate" to "2026-10-22"))
            helper.close()
            val db = Room.databaseBuilder(context, BudgetDatabase::class.java, name)
                .addMigrations(*BudgetDatabase.ALL_MIGRATIONS).addCallback(ru.mybudget.app.data.BalanceLedgerSchema.callback).build()
            try {
                val opened = db.openHelper.writableDatabase // Room validates the entire target schema here.
                assertEquals(50, opened.version)
                assertEquals(900.0, db.budgetDao().getCategoryById(1)!!.currentBalance, 0.0)
                assertEquals(900.0, db.budgetDao().getCategoryById(2)!!.currentBalance, 0.0)
                assertEquals(2, db.budgetDao().getAllTransactionsOnce().size)
                val repair = db.autoDao().getRepairById(1)!!
                assertEquals(1L, repair.paidTransactionId)
                assertEquals("budget", repair.paymentMethod)
                val income = db.budgetDao().getTransactionById(2)!!
                assertEquals(if (version >= 44) 1 else null, income.plannedIncomeSourceId)
                assertEquals(if (version >= 44) "2026-10" else null, income.plannedIncomeMonth)
                opened.query("SELECT transactionId, dueDate FROM obligation_payments WHERE id=1").use {
                    assertTrue(it.moveToFirst())
                    if (version >= 45) { assertEquals(1, it.getInt(0)); assertEquals("2026-10-22", it.getString(1)) }
                    else { assertTrue(it.isNull(0)); assertTrue(it.isNull(1)) }
                }
                assertNull(db.autoDao().getSaveCommand("absent"))
                assertNotNull(db.budgetDao().getLedgerState())
                assertEquals(900.0, db.budgetDao().getAllBalanceMovements().sumOf { it.budgetDelta }, 0.0)
                opened.query("PRAGMA foreign_key_check").use { assertEquals(0, it.count) }
                opened.query("PRAGMA integrity_check").use { it.moveToFirst(); assertEquals("ok", it.getString(0)) }
            } finally { db.close() }
        } finally {
            helper.close()
            context.deleteDatabase(name)
        }
    }
}
