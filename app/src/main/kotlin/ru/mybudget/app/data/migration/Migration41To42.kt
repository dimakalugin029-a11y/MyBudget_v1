package ru.mybudget.app.data.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object Migration41To42 {
    val MIGRATION: Migration = object : Migration(41, 42) {
        override fun migrate(database: SupportSQLiteDatabase) {
            for (table in listOf("vehicle_service_logs", "vehicle_insurances", "vehicle_fuel_logs", "vehicle_repairs")) {
                database.execSQL("ALTER TABLE $table ADD COLUMN paymentMethod TEXT NOT NULL DEFAULT 'unpaid'")
                database.execSQL("UPDATE $table SET paymentMethod = 'budget' WHERE paidTransactionId IS NOT NULL")
            }
        }
    }
}
