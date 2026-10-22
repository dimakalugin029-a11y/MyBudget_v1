package ru.mybudget.app.auto

import ru.mybudget.app.MoneyFormat
import java.time.LocalDate
import java.time.YearMonth

object AutoStatisticsHelper {
    data class Summary(
        val entries: List<AutoEntry>,
        val totalPaid: Double,
        val fromBudget: Double,
        val cash: Double,
        val unpaid: Double,
        val byType: Map<AutoEntryType, Double>,
        val byMonth: Map<YearMonth, Double>,
        val fuelLiters: Double,
        val averageFuelPrice: Double?,
    )

    fun summarize(entries: List<AutoEntry>, vehicleId: Int?, from: Long?, to: Long?): Summary {
        require(from == null || to == null || from <= to)
        val visible = entries.filter {
            (vehicleId == null || it.vehicleId == vehicleId) &&
                (from == null || it.dateEpochDay >= from) && (to == null || it.dateEpochDay <= to)
        }
        val paid = visible.filter { it.paid }
        fun sum(items: List<AutoEntry>) = MoneyFormat.roundMoney(items.sumOf { it.amount })
        val monthly = paid.groupBy { YearMonth.from(LocalDate.ofEpochDay(it.dateEpochDay)) }
        val months = sortedMapOf<YearMonth, Double>()
        if (monthly.isNotEmpty()) {
            var month = if (from != null) YearMonth.from(LocalDate.ofEpochDay(from)) else monthly.keys.minOrNull()!!
            val last = if (to != null) YearMonth.from(LocalDate.ofEpochDay(to)) else monthly.keys.maxOrNull()!!
            while (month <= last) {
                months[month] = sum(monthly[month].orEmpty())
                month = month.plusMonths(1)
            }
        }
        val fuel = paid.filter { it.type == AutoEntryType.FUEL }
        val liters = fuel.sumOf { it.liters }
        return Summary(visible, sum(paid), sum(paid.filter { it.paymentMethod == AutoPaymentMethod.BUDGET }),
            sum(paid.filter { it.paymentMethod == AutoPaymentMethod.CASH }), sum(visible.filter { !it.paid }),
            AutoEntryType.entries.associateWith { type -> sum(paid.filter { it.type == type }) }, months,
            liters, if (liters > 0.0) sum(fuel) / liters else null)
    }
}
