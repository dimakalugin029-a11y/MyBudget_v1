package ru.mybudget.app

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import ru.mybudget.app.data.migration.Migration43To44

@RunWith(AndroidJUnit4::class)
class IncomePlanMigrationTest {
    @Test fun legacyIncomeIsPreservedWithoutGuessingItsSource() {
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(ApplicationProvider.getApplicationContext())
                .callback(object : SupportSQLiteOpenHelper.Callback(43) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE transactions (id INTEGER PRIMARY KEY, amount REAL NOT NULL)")
                        db.execSQL("INSERT INTO transactions VALUES (1, 1200.0)")
                    }
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                }).build())
        try {
            val db = helper.writableDatabase
            Migration43To44.MIGRATION.migrate(db)
            db.query("SELECT amount, plannedIncomeSourceId, plannedIncomeMonth FROM transactions").use {
                assertTrue(it.moveToFirst())
                assertEquals(1200.0, it.getDouble(0), 0.001)
                assertTrue(it.isNull(1))
                assertTrue(it.isNull(2))
                assertEquals(1, it.count)
            }
        } finally { helper.close() }
    }
}
