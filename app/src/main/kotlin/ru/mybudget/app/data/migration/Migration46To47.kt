package ru.mybudget.app.data.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import ru.mybudget.app.data.BalanceLedgerSchema

object Migration46To47 {
    val MIGRATION = object : Migration(46, 47) {
        override fun migrate(db: SupportSQLiteDatabase) { BalanceLedgerSchema.install(db) }
    }
}
