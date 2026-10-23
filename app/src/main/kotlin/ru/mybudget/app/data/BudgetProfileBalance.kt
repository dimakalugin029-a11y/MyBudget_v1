package ru.mybudget.app.data

/** One DB snapshot: only active profiles, and root balances without child duplication. */
data class BudgetProfileBalance(val id:Int,val name:String,val sortOrder:Int,val isActive:Boolean,val createdAt:Long,val total:Double)
