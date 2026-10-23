package ru.mybudget.app

import androidx.room.withTransaction
import ru.mybudget.app.data.BudgetDatabase
import ru.mybudget.app.data.TransactionEntity
import java.time.YearMonth

/** Links existing receipts only: never creates transactions or changes balances. */
class IncomeReceiptLinker(private val db:BudgetDatabase) {
    data class Candidate(val rows:List<TransactionEntity>) {
        val amount get()=MoneyFormat.roundMoney(rows.sumOf { it.amount })
        val date get()=rows.minOf { it.date }
        val description get()=rows.first().description
    }
    suspend fun candidates(budgetId:Int,month:YearMonth):List<Candidate> = db.withTransaction {
        val dao=db.budgetDao()
        val ids=dao.getAllCategoriesForExport().filter { it.budgetId==budgetId }.map { it.id }.toSet()
        val (from,to)=MonthlyPlanHelper.monthRangeMs(month.year,month.monthValue)
        val rows=dao.getTransactionsInRange(from,to).filter {
            it.categoryId in ids && it.type=="income" && it.plannedIncomeSourceId==null && it.plannedIncomeMonth==null
        }
        rows.groupBy { if(it.groupId.isNullOrBlank()) "row:${it.id}" else "group:${it.groupId}" }.values
            .filter { group ->
                val key=group.first().groupId
                key.isNullOrBlank() || dao.getTransactionsByGroupId(key).map { it.id }.toSet()==group.map { it.id }.toSet()
            }.map { Candidate(it) }.sortedByDescending { it.date }
    }
    suspend fun link(sourceId:Int,month:YearMonth,expected:List<TransactionEntity>,finalReceipt:Boolean=false) = db.withTransaction {
        val dao=db.budgetDao()
        val source=requireNotNull(dao.getIncomeSourceById(sourceId)) { "Источник дохода удалён" }
        require(source.isActive && dao.getBudgetProfileById(source.budgetId)?.isActive==true) { "План или бюджет больше недоступен" }
        require(expected.isNotEmpty() && expected.map { it.id }.distinct().size==expected.size)
        val (from,to)=MonthlyPlanHelper.monthRangeMs(month.year,month.monthValue)
        val selectedIds=expected.map { it.id }.toSet()
        for(old in expected) {
            val current=requireNotNull(dao.getTransactionById(old.id)) { "Поступление уже удалено" }
            require(current==old && current.type=="income" && current.plannedIncomeSourceId==null && current.plannedIncomeMonth==null) {
                "Поступление изменилось или уже связано с планом. Обновите список"
            }
            require(current.date>=from && current.date<to && dao.getCategoryByIdAny(current.categoryId)?.budgetId==source.budgetId) {
                "Выберите поступление этого бюджета и месяца"
            }
            val group=current.groupId
            require(group.isNullOrBlank() || dao.getTransactionsByGroupId(group).all { it.id in selectedIds }) { "Выберите всё распределение дохода" }
        }
        if(finalReceipt) dao.reopenIncomePlanMonth(sourceId,month.toString())
        expected.forEach { dao.updateTransaction(it.copy(plannedIncomeSourceId=sourceId,plannedIncomeMonth=month.toString(),incomePlanFinal=finalReceipt)) }
    }
}
