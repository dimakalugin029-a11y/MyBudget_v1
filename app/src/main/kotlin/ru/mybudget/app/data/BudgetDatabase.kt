package ru.mybudget.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import ru.mybudget.app.data.migration.Migration19To20
import ru.mybudget.app.data.migration.Migration20To21
import ru.mybudget.app.data.migration.Migration21To22
import ru.mybudget.app.data.migration.Migration22To23
import ru.mybudget.app.data.migration.Migration23To24
import ru.mybudget.app.data.migration.Migration24To25
import ru.mybudget.app.data.migration.Migration25To26
import ru.mybudget.app.data.migration.Migration26To27
import ru.mybudget.app.data.migration.Migration28To29
import ru.mybudget.app.data.migration.Migration29To30
import ru.mybudget.app.data.migration.Migration30To31
import ru.mybudget.app.data.migration.Migration31To32
import ru.mybudget.app.data.migration.Migration32To33
import ru.mybudget.app.data.migration.Migration33To34
import ru.mybudget.app.data.migration.Migration34To35
import ru.mybudget.app.data.migration.Migration35To36
import ru.mybudget.app.data.migration.Migration36To37
import ru.mybudget.app.data.migration.Migration37To38
import ru.mybudget.app.data.migration.Migration27To28

@Database(
    entities = [
        BudgetProfileEntity::class,
        BudgetCategoryEntity::class,
        TransactionEntity::class,
        PaymentReminderEntity::class,
        SavingsGoalEntity::class,
        RecurringTransactionEntity::class,
        PlannedObligationEntity::class,
        ObligationPaymentEntity::class,
        BalanceSnapshotEntity::class,
        PlannedIncomeSourceEntity::class,
        MonthlyCategoryPlanEntity::class,
        AuditActionEntity::class,
        UtilityBillEntity::class,
        UtilityBillPhotoEntity::class,
        UtilitySectionEntity::class,
        UtilityLineItemEntity::class,
        UtilityMeterReadingEntity::class,
        UtilityMeterInfoEntity::class,
        UtilityTemplateSectionEntity::class,
        UtilityTemplateLineEntity::class,
        UtilityTariffEntity::class,
        UtilityPropertyEntity::class,
    ],
    version = 38,
    exportSchema = false,
)
abstract class BudgetDatabase : RoomDatabase() {
    abstract fun budgetDao(): BudgetDao
    abstract fun utilityDao(): UtilityDao

    companion object {
        @Volatile
        private var INSTANCE: BudgetDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE categories ADD COLUMN defaultIncomeAmount REAL NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE payment_reminders (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        title TEXT NOT NULL,
                        amount REAL NOT NULL,
                        categoryId INTEGER NOT NULL,
                        dueDate TEXT NOT NULL,
                        repeatType TEXT NOT NULL,
                        isActive INTEGER NOT NULL DEFAULT 1,
                        createdAt INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE categories ADD COLUMN position INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE categories ADD COLUMN colorHex TEXT NOT NULL DEFAULT ''")
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE savings_goals (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        name TEXT NOT NULL,
                        targetAmount REAL NOT NULL,
                        categoryId INTEGER NOT NULL,
                        deadline TEXT,
                        isActive INTEGER NOT NULL DEFAULT 1
                    )
                    """.trimIndent(),
                )
            }
        }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE recurring_transactions (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        categoryId INTEGER NOT NULL,
                        amount REAL NOT NULL,
                        type TEXT NOT NULL,
                        description TEXT NOT NULL DEFAULT '',
                        repeatType TEXT NOT NULL,
                        nextDueDate TEXT NOT NULL,
                        isActive INTEGER NOT NULL DEFAULT 1,
                        createdAt INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
            }
        }

        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE utility_bills (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        year INTEGER NOT NULL,
                        month INTEGER NOT NULL,
                        apartmentArea REAL NOT NULL,
                        notes TEXT NOT NULL DEFAULT ''
                    )
                    """.trimIndent(),
                )
                database.execSQL(
                    """
                    CREATE TABLE utility_sections (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        billId INTEGER NOT NULL,
                        name TEXT NOT NULL,
                        sortOrder INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                database.execSQL(
                    """
                    CREATE TABLE utility_line_items (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        sectionId INTEGER NOT NULL,
                        groupLabel TEXT NOT NULL DEFAULT '',
                        name TEXT NOT NULL,
                        quantity REAL,
                        tariff REAL,
                        amount REAL NOT NULL,
                        sortOrder INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
            }
        }

        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE utility_meter_readings (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        groupName TEXT NOT NULL DEFAULT '',
                        meterName TEXT NOT NULL,
                        periodLabel TEXT NOT NULL DEFAULT '',
                        readingValue REAL NOT NULL,
                        consumption REAL,
                        sortOrder INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent(),
                )
            }
        }

        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE utility_meter_info (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        meterName TEXT NOT NULL,
                        verificationDateLabel TEXT NOT NULL DEFAULT '',
                        verificationEpochDay INTEGER,
                        sortOrder INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent(),
                )
                database.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_utility_meter_info_meterName ON utility_meter_info(meterName)",
                )
            }
        }

        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE utility_meter_info ADD COLUMN groupName TEXT NOT NULL DEFAULT ''")
                database.execSQL(
                    """
                    UPDATE utility_meter_info SET groupName = (
                        SELECT r.groupName FROM utility_meter_readings r
                        WHERE r.meterName = utility_meter_info.meterName
                        ORDER BY r.sortOrder DESC LIMIT 1
                    )
                    WHERE groupName = ''
                    """.trimIndent(),
                )
                database.execSQL(
                    """
                    INSERT OR IGNORE INTO utility_meter_info
                        (meterName, groupName, verificationDateLabel, verificationEpochDay, sortOrder)
                    SELECT DISTINCT meterName, groupName, '', NULL, 0
                    FROM utility_meter_readings
                    """.trimIndent(),
                )
                database.execSQL("DROP INDEX IF EXISTS index_utility_meter_info_meterName")
                database.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_utility_meter_info_group_meter ON utility_meter_info(groupName, meterName)",
                )
            }
        }

        private val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE utility_template_sections (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        name TEXT NOT NULL,
                        sortOrder INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                database.execSQL(
                    """
                    CREATE TABLE utility_template_lines (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        sectionId INTEGER NOT NULL,
                        groupLabel TEXT NOT NULL DEFAULT '',
                        name TEXT NOT NULL,
                        lineMode TEXT NOT NULL DEFAULT 'qty_tariff',
                        sortOrder INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                database.execSQL(
                    """
                    CREATE TABLE utility_tariffs (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        templateLineId INTEGER NOT NULL,
                        tariff REAL NOT NULL,
                        updatedAt INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                database.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_utility_tariffs_templateLineId ON utility_tariffs(templateLineId)",
                )
            }
        }

        private val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE utility_bills ADD COLUMN budgetPaidAt INTEGER")
                database.execSQL("ALTER TABLE utility_bills ADD COLUMN budgetPaymentSummary TEXT NOT NULL DEFAULT ''")
            }
        }

        private val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE transactions ADD COLUMN groupId TEXT DEFAULT NULL")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_transactions_groupId ON transactions(groupId)")
            }
        }

        private val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS budget_profiles (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        name TEXT NOT NULL,
                        sortOrder INTEGER NOT NULL DEFAULT 0,
                        isActive INTEGER NOT NULL DEFAULT 1,
                        createdAt INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                database.execSQL(
                    """
                    INSERT INTO budget_profiles (id, name, sortOrder, isActive, createdAt)
                    VALUES (1, 'Основной', 0, 1, ${System.currentTimeMillis()})
                    """.trimIndent(),
                )
                database.execSQL("ALTER TABLE categories ADD COLUMN budgetId INTEGER NOT NULL DEFAULT 1")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_categories_budgetId ON categories(budgetId)")
            }
        }

        private val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE utility_bills ADD COLUMN budgetRemainderSummary TEXT NOT NULL DEFAULT ''")
                database.execSQL("ALTER TABLE utility_bills ADD COLUMN receiptPhotoUri TEXT")
            }
        }

        private val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE transactions ADD COLUMN participantLabel TEXT NOT NULL DEFAULT ''")
            }
        }

        private val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS planned_obligations (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        budgetId INTEGER NOT NULL,
                        name TEXT NOT NULL,
                        amount REAL NOT NULL,
                        periodType TEXT NOT NULL,
                        categoryId INTEGER NOT NULL,
                        paychecksPerMonth INTEGER NOT NULL,
                        dueMonth INTEGER NOT NULL,
                        note TEXT NOT NULL,
                        isActive INTEGER NOT NULL,
                        createdAt INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
            }
        }

        private val MIGRATION_18_19 = object : Migration(18, 19) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("DROP INDEX IF EXISTS index_planned_obligations_budgetId")
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS planned_obligations_fix (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        budgetId INTEGER NOT NULL,
                        name TEXT NOT NULL,
                        amount REAL NOT NULL,
                        periodType TEXT NOT NULL,
                        categoryId INTEGER NOT NULL,
                        paychecksPerMonth INTEGER NOT NULL,
                        dueMonth INTEGER NOT NULL,
                        note TEXT NOT NULL,
                        isActive INTEGER NOT NULL,
                        createdAt INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                database.execSQL(
                    """
                    INSERT INTO planned_obligations_fix (
                        id, budgetId, name, amount, periodType, categoryId,
                        paychecksPerMonth, dueMonth, note, isActive, createdAt
                    )
                    SELECT
                        id, budgetId, name, amount, periodType, categoryId,
                        paychecksPerMonth, dueMonth, note, isActive, createdAt
                    FROM planned_obligations
                    """.trimIndent(),
                )
                database.execSQL("DROP TABLE planned_obligations")
                database.execSQL("ALTER TABLE planned_obligations_fix RENAME TO planned_obligations")
            }
        }

        val ALL_MIGRATIONS: Array<Migration> = arrayOf(
            MIGRATION_1_2,
            MIGRATION_2_3,
            MIGRATION_3_4,
            MIGRATION_4_5,
            MIGRATION_5_6,
            MIGRATION_6_7,
            MIGRATION_7_8,
            MIGRATION_8_9,
            MIGRATION_9_10,
            MIGRATION_10_11,
            MIGRATION_11_12,
            MIGRATION_12_13,
            MIGRATION_13_14,
            MIGRATION_14_15,
            MIGRATION_15_16,
            MIGRATION_16_17,
            MIGRATION_17_18,
            MIGRATION_18_19,
            Migration19To20.MIGRATION,
            Migration20To21.MIGRATION,
            Migration21To22.MIGRATION,
            Migration22To23.MIGRATION,
            Migration23To24.MIGRATION,
            Migration24To25.MIGRATION,
            Migration25To26.MIGRATION,
            Migration26To27.MIGRATION,
            Migration27To28.MIGRATION,
            Migration28To29.MIGRATION,
            Migration29To30.MIGRATION,
            Migration30To31.MIGRATION,
            Migration31To32.MIGRATION,
            Migration32To33.MIGRATION,
            Migration33To34.MIGRATION,
            Migration34To35.MIGRATION,
            Migration35To36.MIGRATION,
            Migration36To37.MIGRATION,
            Migration37To38.MIGRATION,
        )

        fun getInstance(context: Context): BudgetDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    BudgetDatabase::class.java,
                    "budget_database",
                )
                    .addMigrations(*ALL_MIGRATIONS)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
