package ru.mybudget.app

import android.content.Context

object WeeklySummaryHelper {
    data class Digest(val title: String, val body: String)

    suspend fun buildDigest(context: Context): Digest? {
        val manager = BudgetManager.getInstance(context)
        val snapshot = PaymentEventLoader.load(context, manager.getActiveBudgetId(), horizonDays = 6)
        val lines = mutableListOf<String>()
        val nextIncome = snapshot.entries.firstOrNull { it.isIncome && it.epochDay >= snapshot.today.toEpochDay() }
        nextIncome?.let {
            lines += context.getString(R.string.weekly_summary_payday, it.title, it.dateLabel,
                (it.epochDay - snapshot.today.toEpochDay()).toInt())
        }
        val payments = snapshot.entries.filter { !it.isIncome && !it.isForecast }
        if (payments.isNotEmpty()) {
            lines += context.getString(R.string.attention_digest_count, payments.size)
            lines += PaymentEventLoader.totalsText(context, snapshot.entries)
        }
        val summary = MainDashboardHelper.loadSummary(context, manager)
        summary.items.filter { it.target == AttentionTarget.BUDGET }.forEach { lines += it.line.title }
        summary.goalsLine?.let { lines += it.subtitle ?: it.title }
        if (lines.isEmpty()) return null
        return Digest(context.getString(R.string.weekly_summary_title), lines.joinToString("\n"))
    }
}
