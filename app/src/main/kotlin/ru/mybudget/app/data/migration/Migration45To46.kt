package ru.mybudget.app.data.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object Migration45To46 {
    val MIGRATION = object : Migration(45, 46) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS auto_save_commands (requestId TEXT NOT NULL PRIMARY KEY, entryType TEXT NOT NULL, entryId INTEGER NOT NULL, vehicleId INTEGER NOT NULL)")
        }
    }
}
