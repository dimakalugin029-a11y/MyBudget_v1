package ru.mybudget.app.data.migration
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
object Migration48To49 {
 val MIGRATION=object:Migration(48,49) {
  override fun migrate(db:SupportSQLiteDatabase) {
   db.execSQL("ALTER TABLE vehicle_fuel_logs ADD COLUMN fuelType TEXT NOT NULL DEFAULT ''")
   db.execSQL("ALTER TABLE vehicle_fuel_logs ADD COLUMN fuelStation TEXT NOT NULL DEFAULT ''")
   db.execSQL("ALTER TABLE vehicle_insurances ADD COLUMN insuranceType TEXT NOT NULL DEFAULT 'osago'")
   db.execSQL("ALTER TABLE vehicle_insurances ADD COLUMN insuranceCompany TEXT NOT NULL DEFAULT ''")
   db.execSQL("ALTER TABLE vehicle_insurances ADD COLUMN policyNumber TEXT NOT NULL DEFAULT ''")
   db.execSQL("ALTER TABLE vehicle_insurances ADD COLUMN insurancePhone TEXT NOT NULL DEFAULT ''")
  }
 }
}
