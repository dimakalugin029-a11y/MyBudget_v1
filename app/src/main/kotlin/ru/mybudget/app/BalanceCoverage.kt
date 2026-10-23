package ru.mybudget.app

import androidx.room.withTransaction
import ru.mybudget.app.data.BudgetDatabase
import ru.mybudget.app.data.MoneyCommandEntity

object BalanceCoverage {
    data class Transfer(val from:Int,val to:Int,val amount:Double)
    fun leaves(categories:List<BudgetCategory>,budget:Int):List<BudgetCategory> {
        val active=categories.filter { it.budgetId==budget && it.isActive }
        val parents=active.map { it.parentId }.toSet()
        return active.filter { it.id !in parents && it.currentBalance.isFinite() }
    }
    fun plan(categories:List<BudgetCategory>,budget:Int,targetId:Int,amounts:Map<Int,Double>):List<Transfer> {
        val leaves=leaves(categories,budget).associateBy { it.id }
        val target=requireNotNull(leaves[targetId]) { "Статья недоступна" }
        require(target.currentBalance<0) { "Перерасход уже погашен. Обновите список" }
        require(amounts.isNotEmpty()) { "Укажите сумму хотя бы у одного источника" }
        val transfers=amounts.map { (id,raw) ->
            val from=requireNotNull(leaves[id]) { "Источник недоступен" }
            val amount=MoneyFormat.roundMoney(raw)
            require(raw.isFinite() && amount>0 && id!=targetId && amount<=MoneyFormat.roundMoney(from.currentBalance)) { "Проверьте сумму источника" }
            Transfer(id,targetId,amount)
        }
        require(transfers.sumOf { it.amount }.isFinite() && MoneyFormat.roundMoney(transfers.sumOf { it.amount })<=MoneyFormat.roundMoney(-target.currentBalance)) { "Сумма превышает перерасход" }
        return transfers
    }
    suspend fun execute(db:BudgetDatabase,budget:Int,target:Int,amounts:Map<Int,Double>,expected:Map<Int,Double>,request:String)=db.withTransaction {
        require(request.isNotBlank())
        val dao=db.budgetDao()
        if(dao.claimMoneyCommand(MoneyCommandEntity(request)) == -1L) return@withTransaction
        require(dao.getBudgetProfileById(budget)?.isActive==true) { "Бюджет удалён" }
        val categories=dao.getAllCategoriesForExport().map { BudgetCategory.fromEntity(it) }
        val transfers=plan(categories,budget,target,amounts)
        for(id in amounts.keys+target) require(categories.firstOrNull { it.id==id }?.currentBalance==expected[id]) { "Остатки изменились. Откройте покрытие заново" }
        transfers.forEach { dao.transferBetweenLeafCategories(it.from,it.to,it.amount,operationId=request) }
    }
}
