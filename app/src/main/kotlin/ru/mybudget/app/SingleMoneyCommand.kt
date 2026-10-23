package ru.mybudget.app

import androidx.room.withTransaction
import ru.mybudget.app.data.BudgetDatabase
import java.time.YearMonth

data class SingleMoneyCommand(
    val requestId:String, val budgetId:Int, val categoryId:Int, val amount:Double,
    val type:String, val description:String, val participant:String="",
    val categoryName:String="", val parentName:String="", val label:String="",
    val plannedSourceId:Int?=null, val plannedMonth:YearMonth?=null, val finalReceipt:Boolean=false,
)

class SingleMoneyCommandExecutor(private val database:BudgetDatabase) {
    enum class Outcome { SAVED,ALREADY_PROCESSED,PLAN_CHANGED }
    data class Result(val command:SingleMoneyCommand,val outcome:Outcome,val categoryBalance:Double)
    suspend fun execute(command:SingleMoneyCommand):Result=database.withTransaction {
        require(command.requestId.isNotBlank() && command.amount.isFinite() && command.amount>0 && command.type in listOf("income","expense")) { "Некорректная денежная команда" }
        val dao=database.budgetDao()
        if(dao.getTransactionsByGroupId(command.requestId).isNotEmpty()) {
            return@withTransaction Result(command,Outcome.ALREADY_PROCESSED,dao.getCategoryByIdAny(command.categoryId)?.currentBalance ?: 0.0)
        }
        val category=requireNotNull(dao.getCategoryById(command.categoryId)) { "Статья больше недоступна" }
        require(category.isActive && category.budgetId==command.budgetId) { "Статья относится к другому бюджету" }
        val applied=if(command.plannedSourceId!=null) {
            require(command.type=="income" && command.plannedMonth!=null)
            IncomePlanExecution(database).receive(command.plannedSourceId,command.plannedMonth,
                listOf(command.categoryId to command.amount),command.description,command.requestId,finalReceipt=command.finalReceipt)
        } else {
            dao.recordSingleWithRequest(command.categoryId,command.amount,command.type,command.description,command.participant,command.requestId)
            true
        }
        val exists=dao.getTransactionsByGroupId(command.requestId).isNotEmpty()
        val outcome=when { !applied && !exists -> Outcome.PLAN_CHANGED; !exists -> Outcome.ALREADY_PROCESSED; else -> Outcome.SAVED }
        Result(command,outcome,dao.getCategoryByIdAny(command.categoryId)?.currentBalance ?: 0.0)
    }
}
