package ru.mybudget.app

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import ru.mybudget.app.data.migration.Migration45To46

@RunWith(AndroidJUnit4::class)
class AutoSaveCommandMigrationTest {
    @Test fun addsUniqueCommandReceiptWithoutChangingExistingRows() {
        val helper = FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(
            ApplicationProvider.getApplicationContext()).callback(object : SupportSQLiteOpenHelper.Callback(45) {
            override fun onCreate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE sentinel (id INTEGER PRIMARY KEY, amount REAL NOT NULL)")
                db.execSQL("INSERT INTO sentinel VALUES (1, 123.45)")
            }
            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }).build())
        try {
            val db = helper.writableDatabase
            Migration45To46.MIGRATION.migrate(db)
            db.query("SELECT amount FROM sentinel").use { it.moveToFirst(); assertEquals(123.45, it.getDouble(0), 0.0) }
            db.execSQL("INSERT INTO auto_save_commands VALUES ('request', 'REPAIR', 5, 1)")
            assertTrue(runCatching { db.execSQL("INSERT INTO auto_save_commands VALUES ('request', 'REPAIR', 6, 1)") }.isFailure)
        } finally { helper.close() }
    }
}
