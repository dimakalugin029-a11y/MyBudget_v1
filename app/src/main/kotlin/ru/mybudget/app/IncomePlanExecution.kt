package ru.mybudget.app

import androidx.room.withTransaction
import ru.mybudget.app.data.BudgetDatabase
import ru.mybudget.app.data.TransactionEntity
import java.time.YearMonth

/** A receipt is derived from real income transactions, so edits and cancellation stay consistent. */
class IncomePlanExecution(private val database: BudgetDatabase) {
    suspend fun receive(sourceId: Int, month: YearMonth, items: List<Pair<Int, Double>>,
        description: String, requestId: String, replaceGroupId: String? = null): Boolean = database.withTransaction {
        val dao = database.budgetDao()
        require(requestId.isNotBlank())
        if (dao.getTransactionsByGroupId(requestId).isNotEmpty()) return@withTransaction false
        if (dao.claimMoneyCommand(ru.mybudget.app.data.MoneyCommandEntity(requestId)) == -1L) return@withTransaction false
        val source = requireNotNull(dao.getIncomeSourceById(sourceId)) { "Источник дохода удалён" }
        require(source.isActive) { "Источник дохода отключён" }
        if (replaceGroupId != null) {
            require(replaceGroupId != requestId)
            val original = dao.getTransactionsByGroupId(replaceGroupId)
            require(original.isNotEmpty() && original.all { it.type == "income" &&
                it.plannedIncomeSourceId == sourceId && it.plannedIncomeMonth == month.toString() }) {
                "Исходное поступление уже изменено или отменено"
            }
            dao.cancelTransactionGroup(replaceGroupId)
        }
        val override = dao.getMonthlyIncomePlansForMonth(source.budgetId, month.year, month.monthValue)
            .firstOrNull { it.sourceId == sourceId }
        val plan = IncomePlanAmounts.planned(source, month.monthValue, override)
        val received = dao.getIncomePlanTransactions(source.budgetId).filter {
            it.plannedIncomeSourceId == sourceId && it.plannedIncomeMonth == month.toString()
        }.sumOf { it.amount }
        val normalized = items.map { (id, amount) ->
            require(amount.isFinite() && amount > 0.0) { "Некорректная сумма" }
            val category = requireNotNull(dao.getCategoryById(id)) { "Статья удалена" }
            require(category.isActive && category.budgetId == source.budgetId) { "Выберите статью этого бюджета" }
            id to MoneyFormat.roundMoney(amount)
        }
        val total = MoneyFormat.roundMoney(normalized.sumOf { it.second })
        require(normalized.isNotEmpty() && normalized.all { it.second > 0.0 }) { "Укажите сумму" }
        require(total <= MoneyFormat.roundMoney(plan - received) + 0.001) {
            "Сумма превышает остаток по плану. Обновите план или запишите превышение отдельным доходом."
        }
        normalized.forEach { (id, amount) ->
            dao.insertTransaction(TransactionEntity(categoryId = id, amount = amount, type = "income",
                description = description, groupId = requestId, plannedIncomeSourceId = sourceId,
                plannedIncomeMonth = month.toString()))
            dao.applyBalanceDelta(id, amount, "income", requestId)
        }
        true
    }
}
