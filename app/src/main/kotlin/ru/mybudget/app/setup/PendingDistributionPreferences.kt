package ru.mybudget.app.setup

import android.content.Context
import ru.mybudget.app.BudgetApplication

object PendingDistributionPreferences {
    private const val KEY_AMOUNT = "pending_income_amount"
    private const val KEY_BUDGET_ID = "pending_income_budget_id"
    private const val KEY_NOTE = "pending_income_note"

    data class Pending(
        val budgetId: Int,
        val amount: Double,
        val note: String,
    )

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(BudgetApplication.PREFS_NAME, Context.MODE_PRIVATE)

    fun setPending(context: Context, budgetId: Int, amount: Double, note: String = "") {
        if (amount <= 0.0) {
            clear(context)
            return
        }
        prefs(context).edit()
            .putLong(KEY_AMOUNT, java.lang.Double.doubleToRawLongBits(amount))
            .putInt(KEY_BUDGET_ID, budgetId)
            .putString(KEY_NOTE, note)
            .apply()
    }

    fun getPending(context: Context): Pending? {
        val p = prefs(context)
        if (!p.contains(KEY_AMOUNT)) return null
        val amount = java.lang.Double.longBitsToDouble(p.getLong(KEY_AMOUNT, 0L))
        if (amount <= 0.0) return null
        return Pending(
            budgetId = p.getInt(KEY_BUDGET_ID, ActiveBudgetPreferences.getActiveBudgetId(context)),
            amount = amount,
            note = p.getString(KEY_NOTE, "").orEmpty(),
        )
    }

    fun clear(context: Context) {
        prefs(context).edit()
            .remove(KEY_AMOUNT)
            .remove(KEY_BUDGET_ID)
            .remove(KEY_NOTE)
            .apply()
    }
}
