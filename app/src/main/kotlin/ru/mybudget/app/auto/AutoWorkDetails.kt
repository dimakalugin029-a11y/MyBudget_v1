package ru.mybudget.app.auto

import ru.mybudget.app.MoneyFormat

/** Shared interpretation of legacy description rows; unknown lines remain comments. */
object AutoWorkDetails {
    data class Details(val comment:String,val items:List<Pair<String,Double>>)
    fun item(line:String):Pair<String,Double>? {
        val index=line.lastIndexOf(" — ")
        if(index<=0) return null
        val amount=MoneyFormat.parseQuantity(line.substring(index+3).trim().removeSuffix("₽")) ?: return null
        if(!amount.isFinite()) return null
        return line.substring(0,index).trim() to amount
    }
    fun parse(description:String):Details {
        val comments=mutableListOf<String>()
        val items=mutableListOf<Pair<String,Double>>()
        description.lines().forEach { line -> val row=item(line);if(row==null) comments.add(line) else items.add(row) }
        return Details(comments.joinToString("\n").trim(),items)
    }
}
