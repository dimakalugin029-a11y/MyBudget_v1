package ru.mybudget.app.data.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object Migration47To48 {
    val MIGRATION = object : Migration(47,48) {
        override fun migrate(db:SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE vehicles ADD COLUMN isArchived INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE vehicle_service_logs ADD COLUMN isArchived INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE vehicle_insurances ADD COLUMN isArchived INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE vehicle_fuel_logs ADD COLUMN isArchived INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE vehicle_repairs ADD COLUMN isArchived INTEGER NOT NULL DEFAULT 0")
        }
    }
}
