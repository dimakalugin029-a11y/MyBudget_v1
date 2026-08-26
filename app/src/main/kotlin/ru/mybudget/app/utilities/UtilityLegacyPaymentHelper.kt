package ru.mybudget.app.utilities

import android.content.Context
import ru.mybudget.app.R
import ru.mybudget.app.data.UtilityBillEntity
import ru.mybudget.app.data.UtilityDao
import java.time.YearMonth
import java.time.ZoneId

object UtilityLegacyPaymentHelper {
    const val LEGACY_GROUP_ID = "__legacy_paid__"

    fun isLegacyPaid(bill: UtilityBillEntity): Boolean =
        bill.budgetPaymentGroupId == LEGACY_GROUP_ID

    fun paidAtEpochMillis(bill: UtilityBillEntity): Long {
        val endOfMonth = YearMonth.of(bill.year, bill.month).atEndOfMonth()
        return endOfMonth.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }

    fun isEligibleForLegacyMark(
        bill: UtilityBillEntity,
        grandTotal: Double,
        currentMonth: YearMonth,
    ): Boolean {
        if (bill.budgetPaidAt != null) return false
        if (grandTotal <= 0.0) return false
        return YearMonth.of(bill.year, bill.month).isBefore(currentMonth)
    }

    suspend fun countPastUnpaidWithAmount(
        utilityDao: UtilityDao,
        currentMonth: YearMonth = YearMonth.now(),
    ): Int {
        val totals = utilityDao.getBillGrandTotals().associate { it.billId to it.total }
        return utilityDao.getAllBills().count { bill ->
            isEligibleForLegacyMark(bill, totals[bill.id] ?: 0.0, currentMonth)
        }
    }

    suspend fun markPastAsLegacyPaid(
        context: Context,
        utilityDao: UtilityDao,
        currentMonth: YearMonth = YearMonth.now(),
    ): Int {
        val summary = context.getString(R.string.utility_paid_legacy_summary)
        val totals = utilityDao.getBillGrandTotals().associate { it.billId to it.total }
        var count = 0
        for (bill in utilityDao.getAllBills()) {
            if (!isEligibleForLegacyMark(bill, totals[bill.id] ?: 0.0, currentMonth)) continue
            utilityDao.updateBill(
                bill.copy(
                    budgetPaidAt = paidAtEpochMillis(bill),
                    budgetPaymentSummary = summary,
                    budgetPaymentGroupId = LEGACY_GROUP_ID,
                    budgetRemainderSummary = "",
                ),
            )
            count++
        }
        return count
    }

    suspend fun markBillAsLegacyPaid(
        context: Context,
        utilityDao: UtilityDao,
        bill: UtilityBillEntity,
    ) {
        val summary = context.getString(R.string.utility_paid_legacy_summary)
        utilityDao.updateBill(
            bill.copy(
                budgetPaidAt = paidAtEpochMillis(bill),
                budgetPaymentSummary = summary,
                budgetPaymentGroupId = LEGACY_GROUP_ID,
                budgetRemainderSummary = "",
            ),
        )
    }

    suspend fun clearLegacyPaid(utilityDao: UtilityDao, bill: UtilityBillEntity) {
        utilityDao.clearBudgetPayment(bill.id)
    }
}
