package ru.mybudget.app.data.migration
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
object Migration49To50 {
 val MIGRATION=object:Migration(49,50) {
  override fun migrate(db:SupportSQLiteDatabase) { db.execSQL("ALTER TABLE transactions ADD COLUMN incomePlanFinal INTEGER NOT NULL DEFAULT 0") }
 }
}
