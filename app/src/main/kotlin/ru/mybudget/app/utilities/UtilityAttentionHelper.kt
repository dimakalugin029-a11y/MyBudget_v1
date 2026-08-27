package ru.mybudget.app.utilities

import android.content.Context
import ru.mybudget.app.R
import ru.mybudget.app.data.UtilityDao
import ru.mybudget.app.setup.UtilityPaymentReminderPreferences
import java.time.LocalDate
import java.time.YearMonth

object UtilityAttentionHelper {
    const val LEAD_DAYS = 5

    fun effectiveReminderDay(reminderDay: Int, today: LocalDate): Int {
        if (reminderDay <= 0) return today.lengthOfMonth()
        return minOf(reminderDay, today.lengthOfMonth())
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
        val properties = utilityDao.getAllProperties()
        if (properties.isEmpty()) return null

        val today = LocalDate.now()
        val totals = utilityDao.getBillGrandTotals().associate { it.billId to it.total }
        val currentMonth = YearMonth.from(today)
        val multiProperty = properties.size > 1
        val lines = properties.mapNotNull { property ->
            buildPropertyAttentionLine(
                context = context,
                utilityDao = utilityDao,
                propertyId = property.id,
                propertyName = property.name,
                multiProperty = multiProperty,
                reminderDay = UtilityPaymentReminderPreferences.paymentDay(context, property.id),
                totals = totals,
                today = today,
                currentMonth = currentMonth,
            )
        }
        return lines.joinToString("\n").ifBlank { null }
    }

    private suspend fun buildPropertyAttentionLine(
        context: Context,
        utilityDao: UtilityDao,
        propertyId: Int,
        propertyName: String,
        multiProperty: Boolean,
        reminderDay: Int,
        totals: Map<Int, Double>,
        today: LocalDate,
        currentMonth: YearMonth,
    ): String? {
        var pastUnpaid = 0
        var currentUnpaidInWindow = false

        for (bill in utilityDao.getAllBills(propertyId)) {
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
        val body = parts.joinToString("\n").ifBlank { return null }
        return if (multiProperty) "$propertyName: $body" else body
    }
}
