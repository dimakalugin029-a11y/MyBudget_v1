package ru.mybudget.app.data.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object Migration34To35 {
    val MIGRATION: Migration = object : Migration(34, 35) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL(
                """
                CREATE TABLE IF NOT EXISTS obligation_payments (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    obligationId INTEGER NOT NULL,
                    periodYear INTEGER NOT NULL,
                    periodMonth INTEGER NOT NULL,
                    amount REAL NOT NULL,
                    paidAt INTEGER NOT NULL,
                    FOREIGN KEY(obligationId) REFERENCES planned_obligations(id) ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            database.execSQL(
                """
                CREATE INDEX IF NOT EXISTS index_obligation_payments_obligationId
                ON obligation_payments(obligationId)
                """.trimIndent(),
            )
            database.execSQL(
                """
                CREATE UNIQUE INDEX IF NOT EXISTS index_obligation_payments_obligationId_periodYear_periodMonth
                ON obligation_payments(obligationId, periodYear, periodMonth)
                """.trimIndent(),
            )
        }
    }
}
