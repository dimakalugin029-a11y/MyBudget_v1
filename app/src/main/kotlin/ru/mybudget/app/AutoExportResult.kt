package ru.mybudget.app

data class AutoExportResult(
    val success: Boolean,
    val filename: String? = null,
    val message: String? = null,
)
