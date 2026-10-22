package ru.mybudget.app.data

data class RecentDashboardTransaction(
    val description: String,
    val categoryName: String,
    val categoryCount: Int,
    val netAmount: Double,
    val date: Long,
    val mixedTypes: Boolean,
    val entryKey: String,
)
