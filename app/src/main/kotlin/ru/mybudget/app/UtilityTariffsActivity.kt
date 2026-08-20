package ru.mybudget.app

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.mybudget.app.data.UtilityTariffRow
import ru.mybudget.app.utilities.UtilityUserTemplate

class UtilityTariffsActivity : AppCompatActivity() {
    private lateinit var adapter: TariffAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_utility_tariffs)
        ScreenHeaderHelper.setup(this, getString(R.string.utility_tariffs_title))
        findViewById<View>(R.id.utilityTariffsHint)?.let {
            ScreenHintHelper.bind(
                this,
                it,
                ScreenHintHelper.Keys.UTILITY_TARIFFS,
                R.string.hint_utility_tariffs,
                showHelpLink = false,
            )
        }
        adapter = TariffAdapter { showTariffDialog(it) }
        findViewById<RecyclerView>(R.id.tariffsRecycler).apply {
            layoutManager = LinearLayoutManager(this@UtilityTariffsActivity)
            this.adapter = this@UtilityTariffsActivity.adapter
        }
        loadTariffs()
    }

    override fun onResume() {
        super.onResume()
        loadTariffs()
    }

    private fun dao() = BudgetManager.getInstance(this).utilityDao

    private fun loadTariffs() {
        lifecycleScope.launch {
            val rows = withContext(Dispatchers.IO) { UtilityUserTemplate.getTariffRows(dao()) }
            adapter.submit(rows)
            val empty = rows.isEmpty()
            findViewById<View>(R.id.tariffsEmpty).visibility = if (empty) View.VISIBLE else View.GONE
            findViewById<View>(R.id.tariffsRecycler).visibility = if (empty) View.GONE else View.VISIBLE
        }
    }

    private fun showTariffDialog(row: UtilityTariffRow) {
        val input = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            hint = getString(R.string.utility_tariff_hint)
            row.tariff?.let { setText(MoneyFormat.format(it)) }
        }
        val pad = (24 * resources.displayMetrics.density).toInt()
        val wrapped = android.widget.FrameLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(input, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        AlertDialog.Builder(this)
            .setTitle(row.line.name)
            .setMessage(row.sectionName)
            .setView(wrapped)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val tariff = MoneyFormat.parse(input.text)
                if (tariff == null || tariff <= 0.0) {
                    Toast.makeText(this, R.string.utility_tariff_invalid, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                lifecycleScope.launch(Dispatchers.IO) {
                    UtilityUserTemplate.setTariff(dao(), row.line.id, tariff)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@UtilityTariffsActivity, R.string.utility_tariff_saved, Toast.LENGTH_SHORT).show()
                        loadTariffs()
                    }
                }
            }
            .setNeutralButton(R.string.utility_tariff_clear) { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    UtilityUserTemplate.setTariff(dao(), row.line.id, null)
                    withContext(Dispatchers.Main) { loadTariffs() }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private class TariffAdapter(
        private val onEdit: (UtilityTariffRow) -> Unit,
    ) : RecyclerView.Adapter<TariffAdapter.Holder>() {
        private var items: List<UtilityTariffRow> = emptyList()

        fun submit(list: List<UtilityTariffRow>) {
            items = list
            notifyDataSetChanged()
        }

        class Holder(v: View) : RecyclerView.ViewHolder(v) {
            val section: TextView = v.findViewById(R.id.tariffSectionName)
            val name: TextView = v.findViewById(R.id.tariffLineName)
            val value: TextView = v.findViewById(R.id.tariffValue)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_utility_tariff, parent, false)
            return Holder(v)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val row = items[position]
            holder.section.text = row.sectionName
            holder.name.text = row.line.name
            holder.value.text = row.tariff?.let { MoneyFormat.formatRub(it) } ?: "—"
            holder.itemView.setOnClickListener { onEdit(row) }
        }

        override fun getItemCount(): Int = items.size
    }
}
