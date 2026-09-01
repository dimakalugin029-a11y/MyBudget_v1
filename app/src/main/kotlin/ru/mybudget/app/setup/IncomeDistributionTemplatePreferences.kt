package ru.mybudget.app.setup

import android.content.Context
import ru.mybudget.app.BudgetApplication
import ru.mybudget.app.MoneyFormat

object IncomeDistributionTemplatePreferences {
    private const val KEY_IDS_PREFIX = "income_dist_tpl_ids_"
    private const val KEY_AMOUNTS_PREFIX = "income_dist_tpl_amts_"
    private const val KEY_TOTAL_PREFIX = "income_dist_tpl_total_"

    data class Template(
        val budgetId: Int,
        val categoryIds: IntArray,
        val amounts: DoubleArray,
        val savedTotalIncome: Double,
    )

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(BudgetApplication.PREFS_NAME, Context.MODE_PRIVATE)

    fun save(context: Context, budgetId: Int, categoryIds: List<Int>, amounts: Map<Int, Double>, totalIncome: Double) {
        val ids = categoryIds.filter { (amounts[it] ?: 0.0) > 0.0 }
        if (ids.isEmpty()) {
            clear(context, budgetId)
            return
        }
        val amts = ids.map { MoneyFormat.roundMoney(amounts[it] ?: 0.0) }
        prefs(context).edit()
            .putString(KEY_IDS_PREFIX + budgetId, ids.joinToString(","))
            .putString(KEY_AMOUNTS_PREFIX + budgetId, amts.joinToString(","))
            .putLong(KEY_TOTAL_PREFIX + budgetId, java.lang.Double.doubleToRawLongBits(totalIncome.coerceAtLeast(0.0)))
            .apply()
    }

    fun load(context: Context, budgetId: Int): Template? {
        val p = prefs(context)
        val idsRaw = p.getString(KEY_IDS_PREFIX + budgetId, null) ?: return null
        val amtsRaw = p.getString(KEY_AMOUNTS_PREFIX + budgetId, null) ?: return null
        val ids = idsRaw.split(",").mapNotNull { it.toIntOrNull() }
        val amts = amtsRaw.split(",").mapNotNull { it.toDoubleOrNull() }
        if (ids.isEmpty() || ids.size != amts.size) return null
        val savedTotal = if (p.contains(KEY_TOTAL_PREFIX + budgetId)) {
            java.lang.Double.longBitsToDouble(p.getLong(KEY_TOTAL_PREFIX + budgetId, 0L))
        } else {
            amts.sum()
        }
        return Template(
            budgetId = budgetId,
            categoryIds = ids.toIntArray(),
            amounts = amts.toDoubleArray(),
            savedTotalIncome = savedTotal,
        )
    }

    fun scaledAmounts(template: Template, totalIncome: Double): Map<Int, Double> {
        val baseTotal = template.savedTotalIncome.coerceAtLeast(0.01)
        val scale = if (totalIncome > 0.0) totalIncome / baseTotal else 1.0
        return template.categoryIds.indices.associate { index ->
            val id = template.categoryIds[index]
            val amount = MoneyFormat.roundMoney(template.amounts[index] * scale)
            id to amount
        }.filterValues { it > 0.0 }
    }

    fun clear(context: Context, budgetId: Int) {
        prefs(context).edit()
            .remove(KEY_IDS_PREFIX + budgetId)
            .remove(KEY_AMOUNTS_PREFIX + budgetId)
            .remove(KEY_TOTAL_PREFIX + budgetId)
            .apply()
    }
}
