package ru.mybudget.app.data.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object Migration42To43 {
    val MIGRATION: Migration = object : Migration(42, 43) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL("ALTER TABLE planned_obligations ADD COLUMN attentionStartMonth TEXT NOT NULL DEFAULT ''")
            // Old expenses were not always linked to paid-period records. Never invent years of arrears.
            database.execSQL("UPDATE planned_obligations SET attentionStartMonth = strftime('%Y-%m', 'now', 'localtime')")
        }
    }
}
