package ru.mybudget.app.data.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object Migration36To37 {
    val MIGRATION: Migration = object : Migration(36, 37) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL(
                "ALTER TABLE planned_income_sources ADD COLUMN periodType TEXT NOT NULL DEFAULT 'monthly'",
            )
            database.execSQL(
                "ALTER TABLE planned_income_sources ADD COLUMN dueMonth INTEGER NOT NULL DEFAULT 1",
            )
        }
    }
}
