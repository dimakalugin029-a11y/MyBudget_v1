package ru.mybudget.app.data

import ru.mybudget.app.MoneyFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class PaymentReminder(
    val id: Long = 0,
    val title: String,
    val amount: Double,
    val categoryId: Long,
    val categoryName: String,
    val dueDate: Date,
    val repeatType: String,
    val isCompleted: Boolean = false,
    val createdAt: Date = Date(),
) {
    fun toPaymentReminderEntity(): PaymentReminderEntity {
        val format = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        return PaymentReminderEntity(
            id = id.toInt(),
            title = title,
            amount = amount,
            categoryId = categoryId.toInt(),
            dueDate = format.format(dueDate),
            repeatType = repeatType,
            isActive = !isCompleted,
            createdAt = createdAt.time,
        )
    }

    fun isOverdue(): Boolean {
        if (isCompleted) return false
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        val due = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(dueDate)
        return due < today
    }

    fun getFormattedAmount(): String = MoneyFormat.formatRub(amount)
}
