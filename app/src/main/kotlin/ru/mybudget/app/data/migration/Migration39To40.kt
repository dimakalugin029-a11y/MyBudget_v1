package ru.mybudget.app.data.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object Migration39To40 {
    val MIGRATION: Migration = object : Migration(39, 40) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL(
                """
                CREATE TABLE IF NOT EXISTS monthly_income_plans (
                    year INTEGER NOT NULL,
                    month INTEGER NOT NULL,
                    sourceId INTEGER NOT NULL,
                    budgetId INTEGER NOT NULL,
                    amount REAL NOT NULL,
                    isEnabled INTEGER NOT NULL,
                    PRIMARY KEY(year, month, sourceId)
                )
                """.trimIndent(),
            )
        }
    }
}
