package ru.mybudget.app

import ru.mybudget.app.data.BudgetDao
import ru.mybudget.app.data.BudgetRepository
import ru.mybudget.app.data.RecurringTransactionEntity
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

object RecurringHelper {
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    suspend fun applyAndAdvance(
        repository: BudgetRepository,
        dao: BudgetDao,
        recurring: RecurringTransactionEntity,
        context: android.content.Context,
    ) {
        val description = recurring.description.ifBlank {
            context.getString(R.string.recurring_default_description)
        }
        repository.recordTransaction(
            categoryId = recurring.categoryId,
            amount = recurring.amount,
            type = recurring.type,
            description = description,
        )
        advanceNextDate(dao, recurring)
    }

    suspend fun skipAndAdvance(dao: BudgetDao, recurring: RecurringTransactionEntity) {
        advanceNextDate(dao, recurring)
    }

    private suspend fun advanceNextDate(dao: BudgetDao, recurring: RecurringTransactionEntity) {
        val next = computeNextRecurringDate(recurring.nextDueDate, recurring.repeatType)
        if (next != null) {
            dao.updateRecurringNextDate(recurring.id, next)
        } else {
            dao.deleteRecurring(recurring.id)
        }
    }

    fun computeNextRecurringDate(currentStr: String, repeatType: String): String? {
        val current = runCatching { dateFormat.parse(currentStr) }.getOrNull() ?: return null
        val calendar = Calendar.getInstance().apply { time = current }
        when (repeatType) {
            "daily" -> calendar.add(Calendar.DAY_OF_YEAR, 1)
            "weekly" -> calendar.add(Calendar.WEEK_OF_YEAR, 1)
            PlannedObligationHelper.PERIOD_MONTHLY -> calendar.add(Calendar.MONTH, 1)
            else -> return null
        }
        return dateFormat.format(calendar.time)
    }
}
