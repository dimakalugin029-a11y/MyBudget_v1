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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.mybudget.app.data.UtilityMeterInfoEntity
import ru.mybudget.app.utilities.MeterDateParser
import ru.mybudget.app.utilities.MeterRepository
import ru.mybudget.app.utilities.UtilityMeterDialogs

class UtilityMeterVerificationActivity : AppCompatActivity() {
    private lateinit var repository: MeterRepository
    private lateinit var adapter: VerificationAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_meter_verification)
        ScreenHeaderHelper.setup(this, getString(R.string.meter_verification_title), getString(R.string.utility_icon_verification))
        repository = MeterRepository(BudgetManager.getInstance(this).utilityDao)
        adapter = VerificationAdapter { meter ->
            UtilityMeterDialogs.showEditVerificationDate(this, lifecycleScope, repository, meter) { loadList() }
        }
        findViewById<RecyclerView>(R.id.verificationRecyclerView).apply {
            layoutManager = LinearLayoutManager(this@UtilityMeterVerificationActivity)
            adapter = this@UtilityMeterVerificationActivity.adapter
        }
        loadList()
    }

    override fun onResume() {
        super.onResume()
        loadList()
    }

    private fun loadList() {
        lifecycleScope.launch {
            val meters = withContext(Dispatchers.IO) { repository.getAllMeterInfos() }
            adapter.submit(meters)
            val empty = meters.isEmpty()
            findViewById<View>(R.id.verificationEmptyText).visibility = if (empty) View.VISIBLE else View.GONE
            findViewById<View>(R.id.verificationRecyclerView).visibility = if (empty) View.GONE else View.VISIBLE
        }
    }

    private class VerificationAdapter(
        private val onEdit: (UtilityMeterInfoEntity) -> Unit,
    ) : RecyclerView.Adapter<VerificationAdapter.Holder>() {
        private var items: List<UtilityMeterInfoEntity> = emptyList()

        fun submit(list: List<UtilityMeterInfoEntity>) {
            items = list
            notifyDataSetChanged()
        }

        class Holder(v: View) : RecyclerView.ViewHolder(v) {
            val meterName: TextView = v.findViewById(R.id.verificationMeterName)
            val date: TextView = v.findViewById(R.id.verificationDate)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_meter_verification, parent, false)
            return Holder(v)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = items[position]
            val name = item.meterName.trim()
            holder.meterName.text = if (item.groupName.isBlank()) name else "${item.groupName} · $name"
            holder.date.text = formatVerificationDisplay(item.verificationDateLabel)
            holder.itemView.setOnClickListener { onEdit(item) }
        }

        private fun formatVerificationDisplay(raw: String): String {
            val t = raw.trim()
            if (t.isBlank() || !MeterDateParser.looksLikeVerificationDate(t)) return "—"
            return t
        }

        override fun getItemCount(): Int = items.size
    }
}
