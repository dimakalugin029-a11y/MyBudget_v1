package ru.mybudget.app.data.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object Migration35To36 {
    val MIGRATION: Migration = object : Migration(35, 36) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL(
                """
                CREATE INDEX IF NOT EXISTS index_obligation_payments_obligationId
                ON obligation_payments(obligationId)
                """.trimIndent(),
            )
        }
    }
}
