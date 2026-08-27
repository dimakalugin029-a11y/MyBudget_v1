package ru.mybudget.app.data.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object Migration20To21 {
    val MIGRATION: Migration = object : Migration(20, 21) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL("ALTER TABLE utility_bills ADD COLUMN budgetPaymentGroupId TEXT")
        }
    }
}

object Migration21To22 {
    val MIGRATION: Migration = object : Migration(21, 22) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL("ALTER TABLE categories ADD COLUMN defaultPlannedAmount REAL NOT NULL DEFAULT 0")
            database.execSQL(
                """
                CREATE TABLE IF NOT EXISTS monthly_category_plans (
                    year INTEGER NOT NULL,
                    month INTEGER NOT NULL,
                    categoryId INTEGER NOT NULL,
                    budgetId INTEGER NOT NULL,
                    plannedAmount REAL NOT NULL DEFAULT 0,
                    isEnabled INTEGER NOT NULL DEFAULT 1,
                    PRIMARY KEY(year, month, categoryId)
                )
                """.trimIndent(),
            )
        }
    }
}

object Migration22To23 {
    val MIGRATION: Migration = object : Migration(22, 23) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL(CREATE_AUDIT_ACTIONS)
        }
    }
}

object Migration23To24 {
    val MIGRATION: Migration = object : Migration(23, 24) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL("DROP INDEX IF EXISTS index_audit_actions_createdAt")
            val exists = database.query(
                "SELECT name FROM sqlite_master WHERE type='table' AND name='audit_actions'",
            ).use { it.moveToFirst() }
            if (!exists) {
                database.execSQL(CREATE_AUDIT_ACTIONS)
                return
            }
            database.execSQL(
                """
                CREATE TABLE IF NOT EXISTS audit_actions_fix (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    actionType TEXT NOT NULL,
                    title TEXT NOT NULL,
                    description TEXT NOT NULL,
                    payload TEXT NOT NULL,
                    createdAt INTEGER NOT NULL,
                    isReverted INTEGER NOT NULL,
                    revertedAt INTEGER
                )
                """.trimIndent(),
            )
            database.execSQL(
                """
                INSERT INTO audit_actions_fix (
                    id, actionType, title, description, payload, createdAt, isReverted, revertedAt
                )
                SELECT id, actionType, title, description, payload, createdAt, isReverted, revertedAt
                FROM audit_actions
                """.trimIndent(),
            )
            database.execSQL("DROP TABLE audit_actions")
            database.execSQL("ALTER TABLE audit_actions_fix RENAME TO audit_actions")
        }
    }
}

object Migration24To25 {
    val MIGRATION: Migration = object : Migration(24, 25) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL(
                """
                CREATE TABLE IF NOT EXISTS utility_bill_photos (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    billId INTEGER NOT NULL,
                    photoType TEXT NOT NULL,
                    storedUri TEXT NOT NULL,
                    sortOrder INTEGER NOT NULL DEFAULT 0,
                    createdAt INTEGER NOT NULL,
                    FOREIGN KEY(billId) REFERENCES utility_bills(id) ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            database.execSQL(
                "CREATE INDEX IF NOT EXISTS index_utility_bill_photos_billId ON utility_bill_photos(billId)",
            )
            database.execSQL(
                """
                INSERT INTO utility_bill_photos (billId, photoType, storedUri, sortOrder, createdAt)
                SELECT id, 'receipt', receiptPhotoUri, 0, ${System.currentTimeMillis()}
                FROM utility_bills
                WHERE receiptPhotoUri IS NOT NULL AND TRIM(receiptPhotoUri) != ''
                """.trimIndent(),
            )
        }
    }
}

object Migration25To26 {
    val MIGRATION: Migration = object : Migration(25, 26) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL(
                "ALTER TABLE planned_obligations ADD COLUMN dueDay INTEGER NOT NULL DEFAULT 0",
            )
        }
    }
}

object Migration26To27 {
    val MIGRATION: Migration = object : Migration(26, 27) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL(
                """
                CREATE TABLE IF NOT EXISTS utility_properties (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    name TEXT NOT NULL,
                    sortOrder INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent(),
            )
            database.execSQL(
                "INSERT INTO utility_properties (id, name, sortOrder) VALUES (1, 'Квартира 1', 0)",
            )
            database.execSQL(
                "ALTER TABLE utility_bills ADD COLUMN propertyId INTEGER NOT NULL DEFAULT 1",
            )
            database.execSQL(
                "ALTER TABLE utility_meter_readings ADD COLUMN propertyId INTEGER NOT NULL DEFAULT 1",
            )
            database.execSQL(
                "ALTER TABLE utility_meter_info ADD COLUMN propertyId INTEGER NOT NULL DEFAULT 1",
            )
            database.execSQL(
                "ALTER TABLE utility_template_sections ADD COLUMN propertyId INTEGER NOT NULL DEFAULT 1",
            )
            database.execSQL(
                "CREATE INDEX IF NOT EXISTS index_utility_bills_propertyId ON utility_bills(propertyId)",
            )
            database.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS index_utility_bills_propertyId_year_month ON utility_bills(propertyId, year, month)",
            )
            database.execSQL(
                "DROP INDEX IF EXISTS index_utility_meter_info_groupName_meterName",
            )
            database.execSQL(
                """
                CREATE UNIQUE INDEX IF NOT EXISTS index_utility_meter_info_propertyId_groupName_meterName
                ON utility_meter_info(propertyId, groupName, meterName)
                """.trimIndent(),
            )
            database.execSQL(
                "CREATE INDEX IF NOT EXISTS index_utility_meter_readings_propertyId ON utility_meter_readings(propertyId)",
            )
            database.execSQL(
                "CREATE INDEX IF NOT EXISTS index_utility_template_sections_propertyId ON utility_template_sections(propertyId)",
            )
        }
    }
}

object Migration27To28 {
    val MIGRATION: Migration = object : Migration(27, 28) {
        override fun migrate(database: SupportSQLiteDatabase) {
            // Схема без FK на utility_properties — выравнивание identity hash Room.
        }
    }
}

private const val CREATE_AUDIT_ACTIONS = """
CREATE TABLE IF NOT EXISTS audit_actions (
    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
    actionType TEXT NOT NULL,
    title TEXT NOT NULL,
    description TEXT NOT NULL,
    payload TEXT NOT NULL,
    createdAt INTEGER NOT NULL,
    isReverted INTEGER NOT NULL,
    revertedAt INTEGER
)
"""
