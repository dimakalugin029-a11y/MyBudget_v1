package ru.mybudget.app.data.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object Migration38To39 {
    val MIGRATION: Migration = object : Migration(38, 39) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL(
                """
                CREATE TABLE IF NOT EXISTS forecast_snapshots (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    budgetId INTEGER NOT NULL,
                    year INTEGER NOT NULL,
                    month INTEGER NOT NULL,
                    scenario TEXT NOT NULL,
                    totalIncome REAL NOT NULL,
                    totalExpense REAL NOT NULL,
                    net REAL NOT NULL,
                    linesJson TEXT NOT NULL,
                    createdAt INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            database.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS index_forecast_snapshots_budgetId_year_month_scenario ON forecast_snapshots(budgetId, year, month, scenario)",
            )
        }
    }
}
