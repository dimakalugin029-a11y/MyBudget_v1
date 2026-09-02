package ru.mybudget.app.data.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object Migration37To38 {
    val MIGRATION: Migration = object : Migration(37, 38) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL(
                """
                CREATE TABLE IF NOT EXISTS balance_snapshots (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    budgetId INTEGER NOT NULL,
                    dayKey INTEGER NOT NULL,
                    totalBalance REAL NOT NULL,
                    recordedAt INTEGER NOT NULL,
                    FOREIGN KEY(budgetId) REFERENCES budget_profiles(id) ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            database.execSQL(
                "CREATE INDEX IF NOT EXISTS index_balance_snapshots_budgetId ON balance_snapshots(budgetId)",
            )
            database.execSQL(
                "CREATE INDEX IF NOT EXISTS index_balance_snapshots_dayKey ON balance_snapshots(dayKey)",
            )
            database.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS index_balance_snapshots_budgetId_dayKey ON balance_snapshots(budgetId, dayKey)",
            )
            val now = System.currentTimeMillis()
            val dayKey = now / 86_400_000L
            database.execSQL(
                """
                INSERT OR REPLACE INTO balance_snapshots (budgetId, dayKey, totalBalance, recordedAt)
                SELECT bp.id, $dayKey,
                    COALESCE((
                        SELECT SUM(c.currentBalance) FROM categories c
                        WHERE c.budgetId = bp.id AND c.parentId = 0 AND c.isActive = 1
                    ), 0),
                    $now
                FROM budget_profiles bp
                WHERE bp.isActive = 1
                """.trimIndent(),
            )
        }
    }
}
