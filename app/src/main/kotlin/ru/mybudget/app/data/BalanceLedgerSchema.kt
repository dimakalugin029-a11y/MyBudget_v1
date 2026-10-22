package ru.mybudget.app.data

import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

object BalanceLedgerSchema {
    val statements = listOf(
        """CREATE TABLE IF NOT EXISTS balance_movements (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, categoryId INTEGER NOT NULL, budgetId INTEGER NOT NULL, delta REAL NOT NULL, budgetDelta REAL NOT NULL, createdAt INTEGER NOT NULL, kind TEXT NOT NULL, operationId TEXT NOT NULL)""",
        """CREATE INDEX IF NOT EXISTS index_balance_movements_budgetId_createdAt ON balance_movements (budgetId, createdAt)""",
        """CREATE INDEX IF NOT EXISTS index_balance_movements_createdAt ON balance_movements (createdAt)""",
        """CREATE TABLE IF NOT EXISTS balance_ledger_state (id INTEGER NOT NULL PRIMARY KEY, startedAt INTEGER NOT NULL, kind TEXT NOT NULL, operationId TEXT NOT NULL)""",
        """CREATE TRIGGER IF NOT EXISTS balance_ledger_insert AFTER INSERT ON categories WHEN EXISTS(SELECT 1 FROM balance_ledger_state WHERE id=1) BEGIN INSERT INTO balance_movements (categoryId, budgetId, delta, budgetDelta, createdAt, kind, operationId) VALUES (NEW.id, NEW.budgetId, NEW.currentBalance, CASE WHEN NEW.parentId=0 AND NEW.isActive=1 THEN NEW.currentBalance ELSE 0 END, CAST(strftime('%s','now') AS INTEGER) * 1000, CASE WHEN COALESCE((SELECT kind FROM balance_ledger_state WHERE id=1),'adjustment')='adjustment' THEN 'create' ELSE COALESCE((SELECT kind FROM balance_ledger_state WHERE id=1),'adjustment') END, COALESCE((SELECT operationId FROM balance_ledger_state WHERE id=1),'')); END""",
        """CREATE TRIGGER IF NOT EXISTS balance_ledger_delete AFTER DELETE ON categories WHEN EXISTS(SELECT 1 FROM balance_ledger_state WHERE id=1) BEGIN INSERT INTO balance_movements (categoryId, budgetId, delta, budgetDelta, createdAt, kind, operationId) VALUES (OLD.id, OLD.budgetId, -OLD.currentBalance, -(CASE WHEN OLD.parentId=0 AND OLD.isActive=1 THEN OLD.currentBalance ELSE 0 END), CAST(strftime('%s','now') AS INTEGER) * 1000, CASE WHEN COALESCE((SELECT kind FROM balance_ledger_state WHERE id=1),'adjustment')='adjustment' THEN 'remove' ELSE COALESCE((SELECT kind FROM balance_ledger_state WHERE id=1),'adjustment') END, COALESCE((SELECT operationId FROM balance_ledger_state WHERE id=1),'')); END""",
        """CREATE TRIGGER IF NOT EXISTS balance_ledger_update AFTER UPDATE ON categories WHEN OLD.budgetId=NEW.budgetId AND (OLD.currentBalance<>NEW.currentBalance OR OLD.parentId<>NEW.parentId OR OLD.isActive<>NEW.isActive) AND EXISTS(SELECT 1 FROM balance_ledger_state WHERE id=1) BEGIN INSERT INTO balance_movements (categoryId, budgetId, delta, budgetDelta, createdAt, kind, operationId) VALUES (NEW.id, NEW.budgetId, NEW.currentBalance-OLD.currentBalance, (CASE WHEN NEW.parentId=0 AND NEW.isActive=1 THEN NEW.currentBalance ELSE 0 END)-(CASE WHEN OLD.parentId=0 AND OLD.isActive=1 THEN OLD.currentBalance ELSE 0 END), CAST(strftime('%s','now') AS INTEGER) * 1000, CASE WHEN COALESCE((SELECT kind FROM balance_ledger_state WHERE id=1),'adjustment')='adjustment' THEN CASE WHEN OLD.parentId<>NEW.parentId THEN 'reclassify' WHEN OLD.isActive<>NEW.isActive THEN CASE WHEN NEW.isActive=1 THEN 'restore' ELSE 'archive' END ELSE 'adjustment' END ELSE COALESCE((SELECT kind FROM balance_ledger_state WHERE id=1),'adjustment') END, COALESCE((SELECT operationId FROM balance_ledger_state WHERE id=1),'')); END""",
        """CREATE TRIGGER IF NOT EXISTS balance_ledger_move AFTER UPDATE ON categories WHEN OLD.budgetId<>NEW.budgetId AND EXISTS(SELECT 1 FROM balance_ledger_state WHERE id=1) BEGIN INSERT INTO balance_movements (categoryId, budgetId, delta, budgetDelta, createdAt, kind, operationId) VALUES (OLD.id, OLD.budgetId, -OLD.currentBalance, -(CASE WHEN OLD.parentId=0 AND OLD.isActive=1 THEN OLD.currentBalance ELSE 0 END), CAST(strftime('%s','now') AS INTEGER) * 1000, CASE WHEN COALESCE((SELECT kind FROM balance_ledger_state WHERE id=1),'adjustment')='adjustment' THEN 'reclassify' ELSE COALESCE((SELECT kind FROM balance_ledger_state WHERE id=1),'adjustment') END, COALESCE((SELECT operationId FROM balance_ledger_state WHERE id=1),''));INSERT INTO balance_movements (categoryId, budgetId, delta, budgetDelta, createdAt, kind, operationId) VALUES (NEW.id, NEW.budgetId, NEW.currentBalance, CASE WHEN NEW.parentId=0 AND NEW.isActive=1 THEN NEW.currentBalance ELSE 0 END, CAST(strftime('%s','now') AS INTEGER) * 1000, CASE WHEN COALESCE((SELECT kind FROM balance_ledger_state WHERE id=1),'adjustment')='adjustment' THEN 'reclassify' ELSE COALESCE((SELECT kind FROM balance_ledger_state WHERE id=1),'adjustment') END, COALESCE((SELECT operationId FROM balance_ledger_state WHERE id=1),'')); END""",
    )
    fun install(db: SupportSQLiteDatabase) {
        statements.forEach { db.execSQL(it) }
        val empty = db.query("SELECT COUNT(*) FROM balance_ledger_state WHERE id=1").use { it.moveToFirst(); it.getInt(0)==0 }
        if (empty) {
            db.execSQL("INSERT INTO balance_ledger_state (id,startedAt,kind,operationId) VALUES (1,CAST(strftime('%s','now') AS INTEGER)*1000,'adjustment','')")
            db.execSQL("INSERT INTO balance_movements (categoryId,budgetId,delta,budgetDelta,createdAt,kind,operationId) SELECT id,budgetId,currentBalance,CASE WHEN parentId=0 AND isActive=1 THEN currentBalance ELSE 0 END,(SELECT startedAt FROM balance_ledger_state WHERE id=1),'opening','baseline' FROM categories")
        }
    }
    val callback = object : RoomDatabase.Callback() {
        override fun onCreate(db: SupportSQLiteDatabase) { install(db) }
        override fun onOpen(db: SupportSQLiteDatabase) {
            db.beginTransaction()
            try { install(db); db.setTransactionSuccessful() } finally { db.endTransaction() }
        }
    }
}
