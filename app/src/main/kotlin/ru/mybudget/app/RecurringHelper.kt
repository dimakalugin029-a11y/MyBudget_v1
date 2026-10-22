package ru.mybudget.app

import ru.mybudget.app.data.BudgetDatabase
import ru.mybudget.app.data.RecurringTransactionEntity

object RecurringHelper {
    suspend fun applyAndAdvance(recurring: RecurringTransactionEntity, context: android.content.Context): Boolean {
        val applied = PaymentExecution(BudgetDatabase.getInstance(context))
            .recurring(recurring.id, recurring.nextDueDate)
        if (applied && recurring.type == "expense") OverspendNotifier.checkAfterExpense(context, recurring.categoryId)
        return applied
    }

    suspend fun skipAndAdvance(recurring: RecurringTransactionEntity,
        context: android.content.Context): Boolean =
        PaymentExecution(BudgetDatabase.getInstance(context))
            .recurring(recurring.id, recurring.nextDueDate, skip = true)

    fun computeNextRecurringDate(currentStr: String, repeatType: String): String? =
        PaymentExecution.nextDate(currentStr, repeatType)
}
