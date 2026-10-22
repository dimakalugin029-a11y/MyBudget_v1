package ru.mybudget.app

object ReminderPaymentHelper {
    suspend fun payReminder(manager: BudgetManager, reminderId: Int, expectedDate: String? = null): Boolean {
        val reminder = manager.repository.getReminderById(reminderId) ?: return false
        val date = expectedDate ?: reminder.dueDate
        val result = PaymentExecution(manager.database).reminder(reminderId, date, true)
        manager.reloadCategoriesFromDatabase()
        if (result) OverspendNotifier.checkAfterExpense(BudgetApplication.instance, reminder.categoryId)
        return result
    }

    suspend fun completeReminder(manager: BudgetManager, reminderId: Int, expectedDate: String? = null): Boolean {
        val date = expectedDate ?: manager.repository.getReminderById(reminderId)?.dueDate ?: return false
        return PaymentExecution(manager.database).reminder(reminderId, date, false)
    }
}
