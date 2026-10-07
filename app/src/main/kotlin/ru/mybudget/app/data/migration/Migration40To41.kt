package ru.mybudget.app.data.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object Migration40To41 {
    val MIGRATION: Migration = object : Migration(40, 41) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL(
                """
                CREATE TABLE IF NOT EXISTS vehicles (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    name TEXT NOT NULL,
                    sortOrder INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent(),
            )
            database.execSQL(
                """
                CREATE TABLE IF NOT EXISTS vehicle_service_logs (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    vehicleId INTEGER NOT NULL,
                    dateEpochDay INTEGER NOT NULL,
                    mileageKm INTEGER,
                    workDescription TEXT NOT NULL DEFAULT '',
                    amount REAL NOT NULL,
                    categoryId INTEGER NOT NULL,
                    paidTransactionId INTEGER,
                    createdAt INTEGER NOT NULL,
                    FOREIGN KEY(vehicleId) REFERENCES vehicles(id) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            database.execSQL(
                """
                CREATE TABLE IF NOT EXISTS vehicle_insurances (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    vehicleId INTEGER NOT NULL,
                    startDateEpochDay INTEGER NOT NULL,
                    endDateEpochDay INTEGER NOT NULL,
                    amount REAL NOT NULL,
                    categoryId INTEGER NOT NULL,
                    paidTransactionId INTEGER,
                    createdAt INTEGER NOT NULL,
                    FOREIGN KEY(vehicleId) REFERENCES vehicles(id) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            database.execSQL(
                """
                CREATE TABLE IF NOT EXISTS vehicle_fuel_logs (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    vehicleId INTEGER NOT NULL,
                    dateEpochDay INTEGER NOT NULL,
                    liters REAL NOT NULL,
                    pricePerLiter REAL NOT NULL,
                    amount REAL NOT NULL,
                    mileageKm INTEGER,
                    categoryId INTEGER NOT NULL,
                    paidTransactionId INTEGER,
                    createdAt INTEGER NOT NULL,
                    FOREIGN KEY(vehicleId) REFERENCES vehicles(id) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            database.execSQL(
                """
                CREATE TABLE IF NOT EXISTS vehicle_repairs (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    vehicleId INTEGER NOT NULL,
                    dateEpochDay INTEGER NOT NULL,
                    mileageKm INTEGER,
                    orderDescription TEXT NOT NULL DEFAULT '',
                    amount REAL NOT NULL,
                    categoryId INTEGER NOT NULL,
                    paidTransactionId INTEGER,
                    createdAt INTEGER NOT NULL,
                    FOREIGN KEY(vehicleId) REFERENCES vehicles(id) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            database.execSQL("CREATE INDEX IF NOT EXISTS index_vehicle_service_logs_vehicleId ON vehicle_service_logs(vehicleId)")
            database.execSQL("CREATE INDEX IF NOT EXISTS index_vehicle_insurances_vehicleId ON vehicle_insurances(vehicleId)")
            database.execSQL("CREATE INDEX IF NOT EXISTS index_vehicle_fuel_logs_vehicleId ON vehicle_fuel_logs(vehicleId)")
            database.execSQL("CREATE INDEX IF NOT EXISTS index_vehicle_repairs_vehicleId ON vehicle_repairs(vehicleId)")
        }
    }
}
