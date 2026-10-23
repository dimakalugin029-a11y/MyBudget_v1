package ru.mybudget.app

import ru.mybudget.app.data.TransactionEntity
import java.time.YearMonth

object IncomeMonthFacts {
    fun receipts(transactions:List<TransactionEntity>,month:YearMonth):List<TransactionEntity> {
        val (from,to)=MonthlyPlanHelper.monthRangeMs(month.year,month.monthValue)
        return transactions.filter {
            if(it.type!="income") false else {
                val linkedMonth=if(it.plannedIncomeSourceId!=null) runCatching { YearMonth.parse(it.plannedIncomeMonth) }.getOrNull() else null
                if(linkedMonth!=null) linkedMonth==month else it.date>=from && it.date<to
            }
        }
    }
    fun unlinked(transactions:List<TransactionEntity>,month:YearMonth)=receipts(transactions,month).filter { it.plannedIncomeSourceId==null }
}
