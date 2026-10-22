package ru.mybudget.app.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Physical balance history; budgetDelta excludes the duplicate contribution of child rows. */
@Entity(tableName = "balance_movements", indices = [Index(value = ["budgetId", "createdAt"]), Index("createdAt")])
data class BalanceMovementEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val categoryId: Int,
    val budgetId: Int,
    val delta: Double,
    val budgetDelta: Double,
    val createdAt: Long,
    val kind: String,
    val operationId: String,
)

@Entity(tableName = "balance_ledger_state")
data class BalanceLedgerStateEntity(
    @PrimaryKey val id: Int = 1,
    val startedAt: Long,
    val kind: String = "adjustment",
    val operationId: String = "",
)
