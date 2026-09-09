package ru.mybudget.app

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.mybudget.app.reports.YearReportPdfExporter

class YearOverviewActivity : AppCompatActivity() {
    private lateinit var manager: BudgetManager
    private lateinit var adapter: MonthAdapter
    private var years: List<Int> = emptyList()
    private var allTransactions: List<ru.mybudget.app.data.TransactionEntity> = emptyList()
    private var categories: List<BudgetCategory> = emptyList()
    private var currentOverview: YearOverviewHelper.YearOverview? = null

    private val exportPdfLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf"),
    ) { uri -> if (uri != null) writePdf(uri) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_year_overview)
        manager = BudgetManager.getInstance(this)
        ScreenHeaderHelper.setup(this, getString(R.string.year_overview_title), getString(R.string.main_icon_statistics))
        adapter = MonthAdapter()
        findViewById<RecyclerView>(R.id.yearOverviewMonthsList).apply {
            layoutManager = LinearLayoutManager(this@YearOverviewActivity)
            this.adapter = this@YearOverviewActivity.adapter
        }
        findViewById<com.google.android.material.button.MaterialButton>(R.id.yearOverviewExportPdf)
            .setOnClickListener { exportPdf() }
        loadData()
    }

    private fun loadData() {
        lifecycleScope.launch {
            val data = withContext(Dispatchers.IO) {
                val txs = manager.repository.getAllTransactions().first()
                val cats = manager.getCategoriesAsync()
                Triple(txs, cats, YearOverviewHelper.availableYears(txs))
            }
            allTransactions = data.first
            categories = data.second
            years = data.third
            val spinner = findViewById<Spinner>(R.id.yearOverviewSpinner)
            spinner.adapter = ArrayAdapter(
                this@YearOverviewActivity,
                android.R.layout.simple_spinner_dropdown_item,
                years.map { it.toString() },
            )
            spinner.setSelection(0, false)
            spinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                    refreshOverview(years[position])
                }
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            }
            if (years.isNotEmpty()) refreshOverview(years.first())
        }
    }

    private fun refreshOverview(year: Int) {
        val overview = YearOverviewHelper.build(year, allTransactions, categories)
        currentOverview = overview
        findViewById<TextView>(R.id.yearOverviewSummary).text = getString(
            R.string.year_overview_summary,
            MoneyFormat.formatRub(overview.totalIncome),
            MoneyFormat.formatRub(overview.totalExpense),
            MoneyFormat.formatRub(overview.totalSaldo),
            overview.transactionCount,
        )
        adapter.submit(overview.months.filter { it.income > 0.0 || it.expense > 0.0 })
        findViewById<TextView>(R.id.yearOverviewTopExpenses).text =
            overview.topExpenses.joinToString("\n") { "${it.name}: ${MoneyFormat.formatRub(it.amount)}" }
                .ifBlank { getString(R.string.stats_no_data) }
    }

    private fun exportPdf() {
        val overview = currentOverview ?: return
        exportPdfLauncher.launch("mybudget_${overview.year}.pdf")
    }

    private fun writePdf(uri: Uri) {
        val overview = currentOverview ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            val ok = runCatching {
                contentResolver.openOutputStream(uri)?.use { stream ->
                    YearReportPdfExporter.write(this@YearOverviewActivity, overview, stream)
                } != null
            }.getOrDefault(false)
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    this@YearOverviewActivity,
                    if (ok) R.string.stats_export_pdf_done else R.string.stats_export_failed,
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    private class MonthAdapter : RecyclerView.Adapter<MonthAdapter.Holder>() {
        private var items: List<YearOverviewHelper.MonthTotals> = emptyList()

        fun submit(data: List<YearOverviewHelper.MonthTotals>) {
            items = data
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_year_overview_month, parent, false)
            return Holder(view)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount(): Int = items.size

        class Holder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val title = itemView.findViewById<TextView>(R.id.yearMonthTitle)
            private val amounts = itemView.findViewById<TextView>(R.id.yearMonthAmounts)

            fun bind(item: YearOverviewHelper.MonthTotals) {
                title.text = item.label
                amounts.text = itemView.context.getString(
                    R.string.year_overview_month_line_short,
                    MoneyFormat.formatRub(item.income),
                    MoneyFormat.formatRub(item.expense),
                    MoneyFormat.formatRub(item.saldo),
                )
            }
        }
    }
}
