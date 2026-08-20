package ru.mybudget.app.data.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object Migration19To20 {
    val MIGRATION: Migration = object : Migration(19, 20) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL("PRAGMA foreign_keys=OFF")
            cleanupOrphans(database)
            migrateCategories(database)
            migrateTransactions(database)
            migratePaymentReminders(database)
            migrateSavingsGoals(database)
            migrateRecurringTransactions(database)
            migratePlannedObligations(database)
            migrateUtilitySections(database)
            migrateUtilityLineItems(database)
            migrateUtilityTemplateLines(database)
            migrateUtilityTariffs(database)
            database.execSQL("PRAGMA foreign_keys=ON")
        }
    }

    private fun cleanupOrphans(database: SupportSQLiteDatabase) {
        database.execSQL(
            "DELETE FROM transactions\nWHERE NOT EXISTS (SELECT 1 FROM categories c WHERE c.id = transactions.categoryId)",
        )
        database.execSQL(
            "DELETE FROM payment_reminders\nWHERE NOT EXISTS (SELECT 1 FROM categories c WHERE c.id = payment_reminders.categoryId)",
        )
        database.execSQL(
            "DELETE FROM savings_goals\nWHERE NOT EXISTS (SELECT 1 FROM categories c WHERE c.id = savings_goals.categoryId)",
        )
        database.execSQL(
            "DELETE FROM recurring_transactions\nWHERE NOT EXISTS (SELECT 1 FROM categories c WHERE c.id = recurring_transactions.categoryId)",
        )
        database.execSQL(
            "UPDATE planned_obligations SET categoryId = 0\nWHERE categoryId != 0\n  AND NOT EXISTS (SELECT 1 FROM categories c WHERE c.id = planned_obligations.categoryId)",
        )
        database.execSQL(
            "DELETE FROM planned_obligations\nWHERE NOT EXISTS (SELECT 1 FROM budget_profiles p WHERE p.id = planned_obligations.budgetId)",
        )
        database.execSQL(
            "UPDATE categories SET parentId = 0\nWHERE parentId != 0\n  AND NOT EXISTS (SELECT 1 FROM categories p WHERE p.id = categories.parentId)",
        )
        database.execSQL(
            "DELETE FROM categories\nWHERE NOT EXISTS (SELECT 1 FROM budget_profiles p WHERE p.id = categories.budgetId)",
        )
        database.execSQL(
            "DELETE FROM utility_line_items\nWHERE NOT EXISTS (SELECT 1 FROM utility_sections s WHERE s.id = utility_line_items.sectionId)",
        )
        database.execSQL(
            "DELETE FROM utility_sections\nWHERE NOT EXISTS (SELECT 1 FROM utility_bills b WHERE b.id = utility_sections.billId)",
        )
        database.execSQL(
            """
            DELETE FROM utility_tariffs
            WHERE NOT EXISTS (
                SELECT 1 FROM utility_template_lines l WHERE l.id = utility_tariffs.templateLineId
            )
            """.trimIndent(),
        )
        database.execSQL(
            """
            DELETE FROM utility_template_lines
            WHERE NOT EXISTS (
                SELECT 1 FROM utility_template_sections s WHERE s.id = utility_template_lines.sectionId
            )
            """.trimIndent(),
        )
    }

    private fun migrateCategories(database: SupportSQLiteDatabase) {
        database.execSQL(
            """
            CREATE TABLE IF NOT EXISTS categories_new (
                id INTEGER NOT NULL,
                name TEXT NOT NULL,
                parentId INTEGER NOT NULL,
                budgetId INTEGER NOT NULL DEFAULT 1,
                plannedAmount REAL NOT NULL,
                currentBalance REAL NOT NULL,
                defaultIncomeAmount REAL NOT NULL,
                isActive INTEGER NOT NULL,
                position INTEGER NOT NULL,
                colorHex TEXT NOT NULL,
                PRIMARY KEY(id),
                FOREIGN KEY(budgetId) REFERENCES budget_profiles(id)
                    ON UPDATE NO ACTION ON DELETE RESTRICT
            )
            """.trimIndent(),
        )
        database.execSQL(
            """
            INSERT INTO categories_new (
                id, name, parentId, budgetId, plannedAmount, currentBalance,
                defaultIncomeAmount, isActive, position, colorHex
            )
            SELECT
                id, name, parentId, budgetId, plannedAmount, currentBalance,
                defaultIncomeAmount, isActive, position, colorHex
            FROM categories
            """.trimIndent(),
        )
        database.execSQL("DROP TABLE categories")
        database.execSQL("ALTER TABLE categories_new RENAME TO categories")
        database.execSQL("CREATE INDEX IF NOT EXISTS index_categories_budgetId ON categories (budgetId)")
    }

    private fun migrateTransactions(database: SupportSQLiteDatabase) {
        database.execSQL(
            """
            CREATE TABLE IF NOT EXISTS transactions_new (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                categoryId INTEGER NOT NULL,
                amount REAL NOT NULL,
                type TEXT NOT NULL,
                description TEXT NOT NULL,
                date INTEGER NOT NULL,
                groupId TEXT DEFAULT NULL,
                participantLabel TEXT NOT NULL,
                FOREIGN KEY(categoryId) REFERENCES categories(id)
                    ON UPDATE NO ACTION ON DELETE RESTRICT
            )
            """.trimIndent(),
        )
        database.execSQL(
            """
            INSERT INTO transactions_new (
                id, categoryId, amount, type, description, date, groupId, participantLabel
            )
            SELECT id, categoryId, amount, type, description, date, groupId, participantLabel
            FROM transactions
            """.trimIndent(),
        )
        database.execSQL("DROP TABLE transactions")
        database.execSQL("ALTER TABLE transactions_new RENAME TO transactions")
        database.execSQL("CREATE INDEX IF NOT EXISTS index_transactions_categoryId ON transactions (categoryId)")
        database.execSQL("CREATE INDEX IF NOT EXISTS index_transactions_groupId ON transactions (groupId)")
    }

    private fun migratePaymentReminders(database: SupportSQLiteDatabase) {
        database.execSQL(
            """
            CREATE TABLE IF NOT EXISTS payment_reminders_new (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                title TEXT NOT NULL,
                amount REAL NOT NULL,
                categoryId INTEGER NOT NULL,
                dueDate TEXT NOT NULL,
                repeatType TEXT NOT NULL,
                isActive INTEGER NOT NULL,
                createdAt INTEGER NOT NULL,
                FOREIGN KEY(categoryId) REFERENCES categories(id)
                    ON UPDATE NO ACTION ON DELETE RESTRICT
            )
            """.trimIndent(),
        )
        database.execSQL(
            """
            INSERT INTO payment_reminders_new (
                id, title, amount, categoryId, dueDate, repeatType, isActive, createdAt
            )
            SELECT id, title, amount, categoryId, dueDate, repeatType, isActive, createdAt
            FROM payment_reminders
            """.trimIndent(),
        )
        database.execSQL("DROP TABLE payment_reminders")
        database.execSQL("ALTER TABLE payment_reminders_new RENAME TO payment_reminders")
        database.execSQL("CREATE INDEX IF NOT EXISTS index_payment_reminders_categoryId ON payment_reminders (categoryId)")
    }

    private fun migrateSavingsGoals(database: SupportSQLiteDatabase) {
        database.execSQL(
            """
            CREATE TABLE IF NOT EXISTS savings_goals_new (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                name TEXT NOT NULL,
                targetAmount REAL NOT NULL,
                categoryId INTEGER NOT NULL,
                deadline TEXT,
                isActive INTEGER NOT NULL,
                FOREIGN KEY(categoryId) REFERENCES categories(id)
                    ON UPDATE NO ACTION ON DELETE RESTRICT
            )
            """.trimIndent(),
        )
        database.execSQL(
            """
            INSERT INTO savings_goals_new (id, name, targetAmount, categoryId, deadline, isActive)
            SELECT id, name, targetAmount, categoryId, deadline, isActive
            FROM savings_goals
            """.trimIndent(),
        )
        database.execSQL("DROP TABLE savings_goals")
        database.execSQL("ALTER TABLE savings_goals_new RENAME TO savings_goals")
        database.execSQL("CREATE INDEX IF NOT EXISTS index_savings_goals_categoryId ON savings_goals (categoryId)")
    }

    private fun migrateRecurringTransactions(database: SupportSQLiteDatabase) {
        database.execSQL(
            """
            CREATE TABLE IF NOT EXISTS recurring_transactions_new (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                categoryId INTEGER NOT NULL,
                amount REAL NOT NULL,
                type TEXT NOT NULL,
                description TEXT NOT NULL,
                repeatType TEXT NOT NULL,
                nextDueDate TEXT NOT NULL,
                isActive INTEGER NOT NULL,
                createdAt INTEGER NOT NULL,
                FOREIGN KEY(categoryId) REFERENCES categories(id)
                    ON UPDATE NO ACTION ON DELETE RESTRICT
            )
            """.trimIndent(),
        )
        database.execSQL(
            """
            INSERT INTO recurring_transactions_new (
                id, categoryId, amount, type, description, repeatType, nextDueDate, isActive, createdAt
            )
            SELECT id, categoryId, amount, type, description, repeatType, nextDueDate, isActive, createdAt
            FROM recurring_transactions
            """.trimIndent(),
        )
        database.execSQL("DROP TABLE recurring_transactions")
        database.execSQL("ALTER TABLE recurring_transactions_new RENAME TO recurring_transactions")
        database.execSQL(
            "CREATE INDEX IF NOT EXISTS index_recurring_transactions_categoryId ON recurring_transactions (categoryId)",
        )
    }

    private fun migratePlannedObligations(database: SupportSQLiteDatabase) {
        database.execSQL(
            """
            CREATE TABLE IF NOT EXISTS planned_obligations_new (
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
                createdAt INTEGER NOT NULL,
                FOREIGN KEY(budgetId) REFERENCES budget_profiles(id)
                    ON UPDATE NO ACTION ON DELETE RESTRICT
            )
            """.trimIndent(),
        )
        database.execSQL(
            """
            INSERT INTO planned_obligations_new (
                id, budgetId, name, amount, periodType, categoryId, paychecksPerMonth,
                dueMonth, note, isActive, createdAt
            )
            SELECT
                id, budgetId, name, amount, periodType, categoryId, paychecksPerMonth,
                dueMonth, note, isActive, createdAt
            FROM planned_obligations
            """.trimIndent(),
        )
        database.execSQL("DROP TABLE planned_obligations")
        database.execSQL("ALTER TABLE planned_obligations_new RENAME TO planned_obligations")
        database.execSQL("CREATE INDEX IF NOT EXISTS index_planned_obligations_budgetId ON planned_obligations (budgetId)")
    }

    private fun migrateUtilitySections(database: SupportSQLiteDatabase) {
        database.execSQL(
            """
            CREATE TABLE IF NOT EXISTS utility_sections_new (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                billId INTEGER NOT NULL,
                name TEXT NOT NULL,
                sortOrder INTEGER NOT NULL,
                FOREIGN KEY(billId) REFERENCES utility_bills(id)
                    ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        database.execSQL(
            "INSERT INTO utility_sections_new (id, billId, name, sortOrder)\nSELECT id, billId, name, sortOrder FROM utility_sections",
        )
        database.execSQL("DROP TABLE utility_sections")
        database.execSQL("ALTER TABLE utility_sections_new RENAME TO utility_sections")
        database.execSQL("CREATE INDEX IF NOT EXISTS index_utility_sections_billId ON utility_sections (billId)")
    }

    private fun migrateUtilityLineItems(database: SupportSQLiteDatabase) {
        database.execSQL(
            """
            CREATE TABLE IF NOT EXISTS utility_line_items_new (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                sectionId INTEGER NOT NULL,
                groupLabel TEXT NOT NULL,
                name TEXT NOT NULL,
                quantity REAL,
                tariff REAL,
                amount REAL NOT NULL,
                sortOrder INTEGER NOT NULL,
                FOREIGN KEY(sectionId) REFERENCES utility_sections(id)
                    ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        database.execSQL(
            """
            INSERT INTO utility_line_items_new (
                id, sectionId, groupLabel, name, quantity, tariff, amount, sortOrder
            )
            SELECT id, sectionId, groupLabel, name, quantity, tariff, amount, sortOrder
            FROM utility_line_items
            """.trimIndent(),
        )
        database.execSQL("DROP TABLE utility_line_items")
        database.execSQL("ALTER TABLE utility_line_items_new RENAME TO utility_line_items")
        database.execSQL("CREATE INDEX IF NOT EXISTS index_utility_line_items_sectionId ON utility_line_items (sectionId)")
    }

    private fun migrateUtilityTemplateLines(database: SupportSQLiteDatabase) {
        database.execSQL(
            """
            CREATE TABLE IF NOT EXISTS utility_template_lines_new (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                sectionId INTEGER NOT NULL,
                groupLabel TEXT NOT NULL,
                name TEXT NOT NULL,
                lineMode TEXT NOT NULL,
                sortOrder INTEGER NOT NULL,
                FOREIGN KEY(sectionId) REFERENCES utility_template_sections(id)
                    ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        database.execSQL(
            """
            INSERT INTO utility_template_lines_new (
                id, sectionId, groupLabel, name, lineMode, sortOrder
            )
            SELECT id, sectionId, groupLabel, name, lineMode, sortOrder
            FROM utility_template_lines
            """.trimIndent(),
        )
        database.execSQL("DROP TABLE utility_template_lines")
        database.execSQL("ALTER TABLE utility_template_lines_new RENAME TO utility_template_lines")
        database.execSQL(
            "CREATE INDEX IF NOT EXISTS index_utility_template_lines_sectionId ON utility_template_lines (sectionId)",
        )
    }

    private fun migrateUtilityTariffs(database: SupportSQLiteDatabase) {
        database.execSQL(
            """
            CREATE TABLE IF NOT EXISTS utility_tariffs_new (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                templateLineId INTEGER NOT NULL,
                tariff REAL NOT NULL,
                updatedAt INTEGER NOT NULL,
                FOREIGN KEY(templateLineId) REFERENCES utility_template_lines(id)
                    ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        database.execSQL(
            "INSERT INTO utility_tariffs_new (id, templateLineId, tariff, updatedAt)\nSELECT id, templateLineId, tariff, updatedAt FROM utility_tariffs",
        )
        database.execSQL("DROP TABLE utility_tariffs")
        database.execSQL("ALTER TABLE utility_tariffs_new RENAME TO utility_tariffs")
        database.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS index_utility_tariffs_templateLineId ON utility_tariffs (templateLineId)",
        )
    }
}
