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
        val plannedSourceId: Int? = null,
        val plannedMonth: String? = null,
        val requestId: String? = null,
        val finalReceipt:Boolean=true,
    )

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(BudgetApplication.PREFS_NAME, Context.MODE_PRIVATE)

    fun setPending(context: Context, budgetId: Int, amount: Double, note: String = "",
        plannedSourceId: Int? = null, plannedMonth: String? = null,
        requestId: String = java.util.UUID.randomUUID().toString(),finalReceipt:Boolean=true) {
        if (amount <= 0.0) {
            clear(context)
            return
        }
        prefs(context).edit()
            .putLong(KEY_AMOUNT, java.lang.Double.doubleToRawLongBits(amount))
            .putInt(KEY_BUDGET_ID, budgetId)
            .putString(KEY_NOTE, note)
            .putInt("pending_plan_source", plannedSourceId ?: 0)
            .putString("pending_plan_month", plannedMonth.takeIf { plannedSourceId != null })
            .putString("pending_receipt_request", requestId)
            .putBoolean("pending_income_final",finalReceipt)
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
            plannedSourceId = p.getInt("pending_plan_source", 0).takeIf { it > 0 },
            plannedMonth = p.getString("pending_plan_month", null),
            requestId = p.getString("pending_receipt_request", null),
            finalReceipt=p.getBoolean("pending_income_final",true),
        )
    }

    fun clear(context: Context) {
        prefs(context).edit()
            .remove(KEY_AMOUNT)
            .remove(KEY_BUDGET_ID)
            .remove(KEY_NOTE)
            .remove("pending_plan_source")
            .remove("pending_plan_month")
            .remove("pending_receipt_request")
            .remove("pending_income_final")
            .apply()
    }
}
