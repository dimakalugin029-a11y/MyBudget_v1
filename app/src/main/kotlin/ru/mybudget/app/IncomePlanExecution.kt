package ru.mybudget.app

import androidx.room.withTransaction
import ru.mybudget.app.data.BudgetDatabase
import ru.mybudget.app.data.TransactionEntity
import java.time.YearMonth

/** A receipt is derived from real income transactions, so edits and cancellation stay consistent. */
class IncomePlanExecution(private val database: BudgetDatabase) {
    suspend fun receive(sourceId: Int, month: YearMonth, items: List<Pair<Int, Double>>,
        description: String, requestId: String, replaceGroupId: String? = null,finalReceipt:Boolean=false): Boolean = database.withTransaction {
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
        require(dao.getBudgetProfileById(source.budgetId)?.isActive == true) { "Бюджет больше недоступен" }
        val normalized = items.map { (id, amount) ->
            require(amount.isFinite() && amount > 0.0) { "Некорректная сумма" }
            val category = requireNotNull(dao.getCategoryById(id)) { "Статья удалена" }
            require(category.isActive && category.budgetId == source.budgetId) { "Выберите статью этого бюджета" }
            id to MoneyFormat.roundMoney(amount)
        }
        val total = MoneyFormat.roundMoney(normalized.sumOf { it.second })
        require(normalized.isNotEmpty() && normalized.all { it.second > 0.0 }) { "Укажите сумму" }
        require(total.isFinite() && total>0.0) { "Некорректная общая сумма" }
        // A forecast is an estimate, never a cap on a confirmed real receipt.
        if(finalReceipt) dao.reopenIncomePlanMonth(sourceId,month.toString())
        normalized.forEach { (id, amount) ->
            dao.insertTransaction(TransactionEntity(categoryId = id, amount = amount, type = "income",
                description = description, groupId = requestId, plannedIncomeSourceId = sourceId,
                plannedIncomeMonth = month.toString(),incomePlanFinal=finalReceipt))
            dao.applyBalanceDelta(id, amount, "income", requestId)
        }
        true
    }
    suspend fun setCompletion(sourceId:Int,month:YearMonth,final:Boolean)=database.withTransaction {
        val dao=database.budgetDao()
        val source=requireNotNull(dao.getIncomeSourceById(sourceId))
        require(source.isActive && dao.getBudgetProfileById(source.budgetId)?.isActive==true)
        val rows=dao.getIncomePlanTransactions(source.budgetId).filter { it.plannedIncomeSourceId==sourceId && it.plannedIncomeMonth==month.toString() }
        require(rows.isNotEmpty()) { "Сначала запишите или свяжите фактическое поступление" }
        dao.reopenIncomePlanMonth(sourceId,month.toString())
        if(final) {
            val last=rows.maxBy { it.id }
            rows.filter { if(last.groupId.isNullOrBlank()) it.id==last.id else it.groupId==last.groupId }
                .forEach { dao.updateTransaction(it.copy(incomePlanFinal=true)) }
        }
    }

}
