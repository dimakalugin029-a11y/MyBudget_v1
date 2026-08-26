package ru.mybudget.app

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import ru.mybudget.app.data.BudgetDatabase
import ru.mybudget.app.reports.ParticipantReportHelper
import ru.mybudget.app.setup.ParticipantPreferences
import java.util.concurrent.TimeUnit

class ParticipantsReportActivity : AppCompatActivity() {
    private enum class Period { WEEK, MONTH }

    private lateinit var adapter: ParticipantAdapter
    private var period = Period.MONTH

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_participants_report)
        ScreenHeaderHelper.setup(
            this,
            getString(R.string.participants_report_title),
            getString(R.string.settings_icon_participants),
        )
        adapter = ParticipantAdapter()
        findViewById<RecyclerView>(R.id.participantsReportList).apply {
            layoutManager = LinearLayoutManager(this@ParticipantsReportActivity)
            adapter = this@ParticipantsReportActivity.adapter
        }
        bindPeriodChips()
        loadReport()
    }

    override fun onResume() {
        super.onResume()
        loadReport()
    }

    private fun bindPeriodChips() {
        val week = findViewById<TextView>(R.id.participantsPeriodWeek)
        val month = findViewById<TextView>(R.id.participantsPeriodMonth)
        fun refresh() {
            week.isSelected = period == Period.WEEK
            month.isSelected = period == Period.MONTH
        }
        week.setOnClickListener {
            period = Period.WEEK
            refresh()
            loadReport()
        }
        month.setOnClickListener {
            period = Period.MONTH
            refresh()
            loadReport()
        }
        refresh()
    }

    private fun loadReport() {
        lifecycleScope.launch {
            val now = System.currentTimeMillis()
            val fromMs = when (period) {
                Period.WEEK -> now - TimeUnit.DAYS.toMillis(7)
                Period.MONTH -> now - TimeUnit.DAYS.toMillis(30)
            }
            val transactions = BudgetDatabase.getInstance(this@ParticipantsReportActivity)
                .budgetDao()
                .getAllTransactions()
                .first()
            val unlabeled = getString(R.string.participants_report_unlabeled)
            val rows = ParticipantReportHelper.buildExpenseReport(
                transactions = transactions,
                fromMs = fromMs,
                toMs = now + 1,
                configuredNames = ParticipantPreferences.getNames(this@ParticipantsReportActivity),
                unlabeledName = unlabeled,
            )
            val total = ParticipantReportHelper.totalExpenses(rows)
            findViewById<TextView>(R.id.participantsReportTotal).text =
                MoneyFormat.formatRub(total)
            findViewById<TextView>(R.id.participantsReportPeriodLabel).text = when (period) {
                Period.WEEK -> getString(R.string.stats_period_week)
                Period.MONTH -> getString(R.string.stats_period_month)
            }
            val empty = findViewById<TextView>(R.id.participantsReportEmpty)
            empty.visibility = if (rows.any { it.count > 0 }) View.GONE else View.VISIBLE
            adapter.submit(rows)
        }
    }

    private class ParticipantAdapter : RecyclerView.Adapter<ParticipantAdapter.Holder>() {
        private var rows: List<ParticipantReportHelper.Row> = emptyList()

        fun submit(items: List<ParticipantReportHelper.Row>) {
            rows = items
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_participant_report_row, parent, false)
            return Holder(view)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(rows[position])

        override fun getItemCount(): Int = rows.size

        class Holder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val name = itemView.findViewById<TextView>(R.id.participantReportName)
            private val meta = itemView.findViewById<TextView>(R.id.participantReportMeta)
            private val amount = itemView.findViewById<TextView>(R.id.participantReportAmount)

            fun bind(row: ParticipantReportHelper.Row) {
                name.text = row.name
                meta.text = itemView.context.resources.getQuantityString(
                    R.plurals.participants_report_operations,
                    row.count,
                    row.count,
                )
                amount.text = MoneyFormat.formatRub(row.total)
            }
        }
    }
}
