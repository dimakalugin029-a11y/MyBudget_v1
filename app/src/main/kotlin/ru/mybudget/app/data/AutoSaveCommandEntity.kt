package ru.mybudget.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Local receipt of a committed editor command; deliberately not part of user exports. */
@Entity(tableName = "auto_save_commands")
data class AutoSaveCommandEntity(
    @PrimaryKey val requestId: String,
    val entryType: String,
    val entryId: Int,
    val vehicleId: Int,
)
