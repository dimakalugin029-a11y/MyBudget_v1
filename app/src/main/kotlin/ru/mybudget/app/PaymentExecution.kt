package ru.mybudget.app

import androidx.room.withTransaction
import ru.mybudget.app.data.BudgetDatabase
import ru.mybudget.app.data.ObligationPaymentEntity
import ru.mybudget.app.data.PlannedObligationEntity
import java.time.LocalDate
import java.time.YearMonth

/** Every payment entry point serializes its check, posting and schedule changes in Room. */
class PaymentExecution(private val database: BudgetDatabase) {
    private val dao = database.budgetDao()

    suspend fun obligation(id: Int, due: LocalDate, postExpense: Boolean, amountOverride: Double? = null): Boolean =
        database.withTransaction { settleObligation(id, due, postExpense, amountOverride) }

    private suspend fun settleObligation(id: Int, due: LocalDate, post: Boolean, amountOverride: Double?): Boolean {
        val item = dao.getPlannedObligationById(id) ?: return false
        if (!item.isActive || item.categoryId <= 0) return false
        if (dao.isObligationPeriodPaid(id, due.year, due.monthValue)) {
            advanceLinked(item, due)
            return false
        }
        val amount = MoneyFormat.roundMoney(amountOverride ?: item.amount)
        require(amount.isFinite() && amount > 0.0) { "Некорректная сумма платежа" }
        val transactionId = if (post) {
            requireNotNull(dao.getCategoryById(item.categoryId)) { "Статья платежа недоступна" }
            val id = dao.insertTransaction(ru.mybudget.app.data.TransactionEntity(categoryId = item.categoryId,
                amount = amount, type = "expense", description = item.name)).toInt()
            dao.applyBalanceDelta(item.categoryId, -amount)
            id
        } else null
        dao.insertObligationPayment(ObligationPaymentEntity(obligationId = id,
            periodYear = due.year, periodMonth = due.monthValue, amount = amount,
            transactionId = transactionId, dueDate = due.toString()))
        advanceLinked(item, due)
        return true
    }

    private suspend fun advanceLinked(item: PlannedObligationEntity, due: LocalDate) {
        fun matches(value: String) = runCatching {
            YearMonth.from(LocalDate.parse(value)) == YearMonth.from(due)
        }.getOrDefault(false)
        val nextPeriod = if (item.periodType == PlannedObligationHelper.PERIOD_YEARLY)
            YearMonth.from(due).plusYears(1) else YearMonth.from(due).plusMonths(1)
        val next = PlannedObligationHelper.dueLocalDate(nextPeriod, item.dueDay).toString()
        for (reminder in dao.getLinkedReminders(item.id)) {
            if (!matches(reminder.dueDate)) continue
            if (reminder.repeatType == "once") dao.deleteReminder(reminder.id)
            else dao.updateReminderDueDate(reminder.id, next)
        }
        for (recurring in dao.getLinkedRecurring(item.id)) {
            if (matches(recurring.nextDueDate)) dao.updateRecurringNextDate(recurring.id, next)
        }
    }

    suspend fun reminder(id: Int, expectedDate: String, postExpense: Boolean): Boolean = database.withTransaction {
        val item = dao.getReminderById(id) ?: return@withTransaction false
        if (!item.isActive || item.dueDate != expectedDate) return@withTransaction false
        val due = LocalDate.parse(item.dueDate)
        if (item.obligationId != null) {
            return@withTransaction settleObligation(item.obligationId, due, postExpense, null)
        }
        require(item.amount.isFinite() && item.amount > 0.0) { "Некорректная сумма платежа" }
        if (postExpense) {
            requireNotNull(dao.getCategoryById(item.categoryId)) { "Статья платежа недоступна" }
            dao.recordTransaction(item.categoryId, MoneyFormat.roundMoney(item.amount), "expense", item.title)
        }
        val next = nextDate(item.dueDate, item.repeatType)
        if (next == null) dao.deleteReminder(item.id) else dao.updateReminderDueDate(item.id, next)
        true
    }

    suspend fun recurring(id: Int, expectedDate: String, skip: Boolean = false): Boolean = database.withTransaction {
        val item = dao.getRecurringById(id) ?: return@withTransaction false
        if (!item.isActive || item.nextDueDate != expectedDate) return@withTransaction false
        if (!skip && item.obligationId != null) {
            return@withTransaction settleObligation(item.obligationId, LocalDate.parse(expectedDate), true, null)
        }
        if (!skip) {
            requireNotNull(dao.getCategoryById(item.categoryId)) { "Статья операции недоступна" }
            require(item.type == "income" || item.type == "expense")
            require(item.amount.isFinite() && item.amount > 0.0) { "Некорректная сумма операции" }
            dao.recordTransaction(item.categoryId, MoneyFormat.roundMoney(item.amount), item.type, item.description)
        }
        val next = nextDate(item.nextDueDate, item.repeatType)
        if (next == null) dao.deleteRecurring(item.id) else dao.updateRecurringNextDate(item.id, next)
        true
    }

    companion object {
        fun nextDate(date: String, repeat: String): String? {
            val due = runCatching { LocalDate.parse(date) }.getOrNull() ?: return null
            return when (repeat) {
                "daily" -> due.plusDays(1)
                "weekly" -> due.plusWeeks(1)
                "monthly" -> due.plusMonths(1)
                "yearly" -> due.plusYears(1)
                else -> return null
            }.toString()
        }
    }
}
