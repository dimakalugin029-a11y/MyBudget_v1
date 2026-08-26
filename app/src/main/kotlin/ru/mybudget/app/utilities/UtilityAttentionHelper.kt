package ru.mybudget.app.utilities

import android.content.Context
import ru.mybudget.app.R
import ru.mybudget.app.data.UtilityDao
import ru.mybudget.app.setup.MeterReadingReminderPreferences
import java.time.LocalDate
import java.time.YearMonth

object UtilityAttentionHelper {
    const val LEAD_DAYS = 5

    fun effectiveReminderDay(reminderDay: Int, today: LocalDate): Int {
        return minOf(reminderDay.coerceIn(1, 31), today.lengthOfMonth())
    }

    fun attentionWindowStartDay(reminderDay: Int, today: LocalDate): Int {
        return maxOf(1, effectiveReminderDay(reminderDay, today) - LEAD_DAYS)
    }

    fun isWithinAttentionWindow(
        reminderDay: Int,
        today: LocalDate = LocalDate.now(),
    ): Boolean {
        return today.dayOfMonth >= attentionWindowStartDay(reminderDay, today)
    }

    suspend fun buildAttentionLine(context: Context, utilityDao: UtilityDao): String? {
        val today = LocalDate.now()
        val reminderDay = MeterReadingReminderPreferences.reminderDay(context)
        val totals = utilityDao.getBillGrandTotals().associate { it.billId to it.total }
        val currentMonth = YearMonth.from(today)

        var pastUnpaid = 0
        var currentUnpaidInWindow = false

        for (bill in utilityDao.getAllBills()) {
            if (bill.budgetPaidAt != null) continue
            if ((totals[bill.id] ?: 0.0) <= 0.0) continue

            val billMonth = YearMonth.of(bill.year, bill.month)
            when {
                billMonth.isBefore(currentMonth) -> pastUnpaid++
                billMonth == currentMonth &&
                    isWithinAttentionWindow(reminderDay, today) -> currentUnpaidInWindow = true
            }
        }

        val parts = mutableListOf<String>()
        if (pastUnpaid > 0) {
            parts += context.resources.getQuantityString(
                R.plurals.main_utilities_unpaid_summary,
                pastUnpaid,
                pastUnpaid,
            )
        }
        if (currentUnpaidInWindow) {
            parts += context.getString(
                R.string.main_utilities_due_soon,
                effectiveReminderDay(reminderDay, today),
            )
        }
        return parts.joinToString("\n").ifBlank { null }
    }
}
