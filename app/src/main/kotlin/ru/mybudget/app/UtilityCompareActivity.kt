package ru.mybudget.app

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.mybudget.app.data.UtilityBillEntity
import ru.mybudget.app.utilities.MeterRepository
import ru.mybudget.app.utilities.UtilityMonthCompareRow
import ru.mybudget.app.utilities.UtilityUserTemplate

class UtilityCompareActivity : AppCompatActivity() {
    private lateinit var repository: MeterRepository
    private lateinit var adapter: CompareAdapter
    private var bills: List<Pair<UtilityBillEntity, Double>> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_utility_compare)
        ScreenHeaderHelper.setup(this, getString(R.string.utility_compare_title), getString(R.string.main_icon_utilities))
        repository = MeterRepository(BudgetManager.getInstance(this).utilityDao)
        adapter = CompareAdapter()
        findViewById<RecyclerView>(R.id.compareRecycler).apply {
            layoutManager = LinearLayoutManager(this@UtilityCompareActivity)
            adapter = this@UtilityCompareActivity.adapter
        }
        loadBills()
    }

    private fun loadBills() {
        lifecycleScope.launch {
            bills = withContext(Dispatchers.IO) {
                val dao = BudgetManager.getInstance(this@UtilityCompareActivity).utilityDao
                val totals = dao.getBillGrandTotals().associate { it.billId to it.total }
                dao.getAllBills().map { it to (totals[it.id] ?: 0.0) }
            }
            if (bills.size < 2) {
                Toast.makeText(this@UtilityCompareActivity, R.string.utility_compare_need_two, Toast.LENGTH_LONG).show()
                finish()
                return@launch
            }
            val labels = bills.map { (bill, total) ->
                "${UtilityUserTemplate.formatPeriod(bill.year, bill.month)} — ${MoneyFormat.formatRub(total)}"
            }
            val spinnerAdapter = ArrayAdapter(this@UtilityCompareActivity, android.R.layout.simple_spinner_dropdown_item, labels)
            val spinnerA = findViewById<Spinner>(R.id.compareMonthASpinner)
            val spinnerB = findViewById<Spinner>(R.id.compareMonthBSpinner)
            spinnerA.adapter = spinnerAdapter
            spinnerB.adapter = spinnerAdapter
            spinnerA.setSelection(1.coerceAtMost(bills.lastIndex), false)
            spinnerB.setSelection(0, false)
            val listener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    runCompare()
                }

                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
            spinnerA.onItemSelectedListener = listener
            spinnerB.onItemSelectedListener = listener
            runCompare()
        }
    }

    private fun runCompare() {
        val posA = findViewById<Spinner>(R.id.compareMonthASpinner).selectedItemPosition
        val posB = findViewById<Spinner>(R.id.compareMonthBSpinner).selectedItemPosition
        if (posA < 0 || posB < 0 || posA == posB || posA >= bills.size || posB >= bills.size) {
            findViewById<TextView>(R.id.compareSummaryText).setText(R.string.utility_compare_pick_different)
            adapter.submit(emptyList())
            return
        }
        val billA = bills[posA].first.id
        val billB = bills[posB].first.id
        lifecycleScope.launch {
            val compare = withContext(Dispatchers.IO) { repository.compareBills(billA, billB) }
            val diff = compare.totalB - compare.totalA
            val sign = if (diff >= 0.0) "+" else ""
            findViewById<TextView>(R.id.compareSummaryText).text = getString(
                R.string.utility_compare_summary,
                MoneyFormat.format(compare.totalA),
                MoneyFormat.format(compare.totalB),
                sign + MoneyFormat.format(diff),
            )
            adapter.submit(compare.rows)
        }
    }

    private class CompareAdapter : RecyclerView.Adapter<CompareAdapter.Holder>() {
        private var items: List<UtilityMonthCompareRow> = emptyList()

        fun submit(list: List<UtilityMonthCompareRow>) {
            items = list
            notifyDataSetChanged()
        }

        class Holder(v: View) : RecyclerView.ViewHolder(v) {
            val name: TextView = v.findViewById(R.id.compareLineName)
            val values: TextView = v.findViewById(R.id.compareLineValues)
            val diff: TextView = v.findViewById(R.id.compareLineDiff)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_utility_compare_row, parent, false)
            return Holder(v)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val row = items[position]
            holder.name.text = row.lineLabel
            holder.values.text = "${MoneyFormat.formatRub(row.amountA)}  →  ${MoneyFormat.formatRub(row.amountB)}"
            val d = row.diff
            val sign = if (d >= 0.0) "+" else ""
            holder.diff.text = "Δ $sign${MoneyFormat.formatRub(d)}"
            val colorRes = when {
                d > 0.0 -> R.color.expense_red
                d < 0.0 -> R.color.income_green
                else -> R.color.text_secondary
            }
            holder.diff.setTextColor(ContextCompat.getColor(holder.itemView.context, colorRes))
        }

        override fun getItemCount(): Int = items.size
    }
}
