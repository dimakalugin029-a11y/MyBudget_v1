package ru.mybudget.app

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import ru.mybudget.app.data.migration.Migration41To42

@RunWith(AndroidJUnit4::class)
class AutoPaymentMigrationTest {
    @Test fun migrationPreservesRowsAndDistinguishesPaidFromUnpaid() {
        val tables = listOf("vehicle_service_logs", "vehicle_insurances", "vehicle_fuel_logs", "vehicle_repairs")
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(ApplicationProvider.getApplicationContext())
                .callback(object : SupportSQLiteOpenHelper.Callback(41) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        tables.forEach { table ->
                            db.execSQL("CREATE TABLE $table (id INTEGER PRIMARY KEY, paidTransactionId INTEGER)")
                            db.execSQL("INSERT INTO $table VALUES (1, 123), (2, NULL)")
                        }
                    }
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                }).build(),
        )
        try {
            val database = helper.writableDatabase
            Migration41To42.MIGRATION.migrate(database)
            tables.forEach { table ->
                database.query("SELECT id, paidTransactionId, paymentMethod FROM $table ORDER BY id").use {
                    it.moveToFirst()
                    assertEquals(1, it.getInt(0))
                    assertEquals(123L, it.getLong(1))
                    assertEquals("budget", it.getString(2))
                    it.moveToNext()
                    assertEquals("unpaid", it.getString(2))
                    assertEquals(2, it.count)
                }
            }
        } finally {
            helper.close()
        }
    }
}
