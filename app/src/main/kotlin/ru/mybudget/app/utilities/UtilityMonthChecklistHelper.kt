package ru.mybudget.app.utilities

import android.content.Context
import ru.mybudget.app.R
import ru.mybudget.app.data.UtilityBillEntity

object UtilityMonthChecklistHelper {
    data class Checklist(
        val receiptFilled: Boolean,
        val metersRecorded: Boolean,
        val paidFromBudget: Boolean,
        val hasPhoto: Boolean,
    ) {
        fun completedCount(): Int =
            listOf(receiptFilled, metersRecorded, paidFromBudget, hasPhoto).count { it }

        fun totalSteps(): Int = 4
    }

    fun fromBill(bill: UtilityBillEntity, grandTotal: Double, metersRecorded: Boolean): Checklist {
        return Checklist(
            receiptFilled = grandTotal > 0.0,
            metersRecorded = metersRecorded,
            paidFromBudget = bill.budgetPaidAt != null,
            hasPhoto = !bill.receiptPhotoUri.isNullOrBlank(),
        )
    }

    fun formatCompact(context: Context, checklist: Checklist): String {
        val steps = listOf(
            mark(checklist.receiptFilled),
            mark(checklist.metersRecorded),
            mark(checklist.paidFromBudget),
            mark(checklist.hasPhoto),
        ).joinToString("")
        return context.getString(
            R.string.utility_month_checklist_compact,
            steps,
            checklist.completedCount(),
            checklist.totalSteps(),
        )
    }

    fun formatLegend(context: Context): String =
        context.getString(R.string.utility_month_checklist_legend)

    private fun mark(done: Boolean): String = if (done) "✓" else "○"
}
