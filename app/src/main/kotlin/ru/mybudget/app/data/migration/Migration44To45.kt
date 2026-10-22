package ru.mybudget.app.data.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object Migration44To45 {
    val MIGRATION = object : Migration(44, 45) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL("ALTER TABLE obligation_payments ADD COLUMN transactionId INTEGER DEFAULT NULL")
            database.execSQL("ALTER TABLE obligation_payments ADD COLUMN dueDate TEXT DEFAULT NULL")
            database.execSQL("CREATE TABLE IF NOT EXISTS money_commands (requestId TEXT NOT NULL PRIMARY KEY, createdAt INTEGER NOT NULL)")
        }
    }
}
