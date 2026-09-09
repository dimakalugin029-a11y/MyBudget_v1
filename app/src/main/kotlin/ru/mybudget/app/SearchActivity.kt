package ru.mybudget.app

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.mybudget.app.data.BudgetDatabase

class SearchActivity : AppCompatActivity() {
    private lateinit var manager: BudgetManager
    private lateinit var adapter: SearchAdapter
    private var searchJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_search)
        manager = BudgetManager.getInstance(this)
        ScreenHeaderHelper.setup(this, getString(R.string.search_title), "🔍")
        adapter = SearchAdapter { result -> openResult(result) }
        findViewById<RecyclerView>(R.id.searchResultsList).apply {
            layoutManager = LinearLayoutManager(this@SearchActivity)
            this.adapter = this@SearchActivity.adapter
        }
        val input = findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.searchInput)
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                scheduleSearch(s?.toString().orEmpty())
            }
        })
        input.requestFocus()
    }

    private fun scheduleSearch(query: String) {
        searchJob?.cancel()
        searchJob = lifecycleScope.launch {
            delay(250)
            runSearch(query)
        }
    }

    private suspend fun runSearch(query: String) {
        val results = withContext(Dispatchers.IO) {
            val db = BudgetDatabase.getInstance(this@SearchActivity)
            GlobalSearchHelper.search(
                query = query,
                budgetId = manager.getActiveBudgetId(),
                budgetDao = db.budgetDao(),
                utilityDao = db.utilityDao(),
            )
        }
        adapter.submit(results)
        val emptyView = findViewById<TextView>(R.id.searchEmptyText)
        emptyView.visibility = if (query.length >= 2 && results.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun openResult(result: GlobalSearchHelper.SearchResult) {
        when (result.type) {
            GlobalSearchHelper.ResultType.TRANSACTION -> {
                startActivity(Intent(this, TransactionsActivity::class.java))
            }
            GlobalSearchHelper.ResultType.CATEGORY -> {
                val ids = result.categoryIds ?: return
                startActivity(
                    Intent(this, TransactionsActivity::class.java)
                        .putExtra(TransactionsActivity.EXTRA_CATEGORY_IDS, ids)
                        .putExtra(TransactionsActivity.EXTRA_CATEGORY_TITLE, result.title),
                )
            }
            GlobalSearchHelper.ResultType.OBLIGATION -> {
                startActivity(Intent(this, PlannedObligationsActivity::class.java))
            }
            GlobalSearchHelper.ResultType.UTILITY_MONTH -> {
                val billId = result.utilityBillId ?: return
                lifecycleScope.launch {
                    val bill = withContext(Dispatchers.IO) {
                        BudgetDatabase.getInstance(this@SearchActivity).utilityDao().getBillById(billId)
                    } ?: return@launch
                    startActivity(
                        Intent(this@SearchActivity, UtilityBillActivity::class.java)
                            .putExtra(UtilitiesActivity.EXTRA_BILL_ID, bill.id),
                    )
                }
            }
        }
    }

    private class SearchAdapter(
        private val onClick: (GlobalSearchHelper.SearchResult) -> Unit,
    ) : RecyclerView.Adapter<SearchAdapter.Holder>() {
        private var items: List<GlobalSearchHelper.SearchResult> = emptyList()

        fun submit(data: List<GlobalSearchHelper.SearchResult>) {
            items = data
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_search_result, parent, false)
            return Holder(view)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.bind(items[position], onClick)
        }

        override fun getItemCount(): Int = items.size

        class Holder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val typeView = itemView.findViewById<TextView>(R.id.searchResultType)
            private val titleView = itemView.findViewById<TextView>(R.id.searchResultTitle)
            private val subtitleView = itemView.findViewById<TextView>(R.id.searchResultSubtitle)

            fun bind(result: GlobalSearchHelper.SearchResult, onClick: (GlobalSearchHelper.SearchResult) -> Unit) {
                val context = itemView.context
                typeView.text = when (result.type) {
                    GlobalSearchHelper.ResultType.TRANSACTION -> context.getString(R.string.search_type_transaction)
                    GlobalSearchHelper.ResultType.CATEGORY -> context.getString(R.string.search_type_category)
                    GlobalSearchHelper.ResultType.OBLIGATION -> context.getString(R.string.search_type_obligation)
                    GlobalSearchHelper.ResultType.UTILITY_MONTH -> context.getString(R.string.search_type_utility)
                }
                titleView.text = result.title
                subtitleView.text = result.subtitle
                itemView.setOnClickListener { onClick(result) }
            }
        }
    }
}
