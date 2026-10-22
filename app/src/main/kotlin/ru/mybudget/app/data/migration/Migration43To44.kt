package ru.mybudget.app.data.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object Migration43To44 {
    val MIGRATION = object : Migration(43, 44) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL("ALTER TABLE transactions ADD COLUMN plannedIncomeSourceId INTEGER DEFAULT NULL")
            database.execSQL("ALTER TABLE transactions ADD COLUMN plannedIncomeMonth TEXT DEFAULT NULL")
        }
    }
}
