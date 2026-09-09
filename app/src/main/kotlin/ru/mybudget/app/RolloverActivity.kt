package ru.mybudget.app

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.mybudget.app.data.BudgetDatabase
import ru.mybudget.app.setup.RolloverPreferences
import ru.mybudget.app.setup.SavingsReservePreferences

class RolloverActivity : AppCompatActivity() {
    private lateinit var budgetManager: BudgetManager
    private lateinit var adapter: RolloverAdapter
    private var reserveLines: List<SavingsReserveHelper.Line> = emptyList()
    private var targetOptions: List<Pair<BudgetCategory, String>> = emptyList()
    private var reservePercent = SavingsReservePreferences.DEFAULT_PERCENT

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_rollover)
        ScreenHeaderHelper.setup(this, getString(R.string.rollover_title), getString(R.string.main_icon_budget))
        findViewById<View>(R.id.rolloverHint)?.let {
            ScreenHintHelper.bind(this, it, ScreenHintHelper.Keys.ROLLOVER, R.string.rollover_hint, showHelpLink = false)
        }
        budgetManager = BudgetManager.getInstance(this)
        reservePercent = SavingsReservePreferences.getReservePercent(this)
        adapter = RolloverAdapter { refreshReserveTotal() }
        findViewById<RecyclerView>(R.id.rolloverRecyclerView).apply {
            layoutManager = LinearLayoutManager(this@RolloverActivity)
            this.adapter = this@RolloverActivity.adapter
        }
        findViewById<MaterialButton>(R.id.rolloverReserveButton).setOnClickListener { transferReserve() }
        findViewById<MaterialButton>(R.id.rolloverTransferButton).setOnClickListener { transferSelected() }
        findViewById<MaterialButton>(R.id.rolloverKeepButton).setOnClickListener { finishKeeping() }
        setupPercentSpinner()
        loadData()
    }

    private fun setupPercentSpinner() {
        val spinner = findViewById<Spinner>(R.id.rolloverReservePercentSpinner)
        val labels = SavingsReservePreferences.PERCENT_OPTIONS.map {
            getString(R.string.rollover_reserve_percent_option, it)
        }
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        val index = SavingsReservePreferences.PERCENT_OPTIONS.indexOf(reservePercent).coerceAtLeast(0)
        spinner.setSelection(index, false)
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                reservePercent = SavingsReservePreferences.PERCENT_OPTIONS[position]
                SavingsReservePreferences.setReservePercent(this@RolloverActivity, reservePercent)
                adapter.updatePercent(reservePercent)
                refreshReserveTotal()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    private fun loadData() {
        lifecycleScope.launch(Dispatchers.IO) {
            val all = budgetManager.getCategoriesAsync()
            val budgetId = budgetManager.getActiveBudgetId()
            val parents = all.associate { it.id to it.name }
            val candidates = BudgetRolloverHelper.candidates(all, parents, budgetId) {
                budgetManager.hasSubcategories(it)
            }
            val targets = all
                .filter { cat ->
                    cat.budgetId == budgetId &&
                        cat.isActive &&
                        cat.parentId != 0 &&
                        !budgetManager.hasSubcategories(cat.id)
                }
                .map { cat ->
                    val prefix = if (cat.parentId == 0) "" else (parents[cat.parentId]?.let { "$it → " } ?: "")
                    cat to (prefix + cat.name)
                }

            val previousMonth = MonthStartSummaryHelper.previousMonth()
            val (fromMs, toMs) = MonthBudgetComparisonHelper.monthRangeMs(previousMonth.year, previousMonth.month)
            val dao = BudgetDatabase.getInstance(this@RolloverActivity).budgetDao()
            val monthlyPlans = dao.getMonthlyPlansForBudgetMonth(
                budgetId,
                previousMonth.year,
                previousMonth.month,
            ).associateBy { it.categoryId }
            val spentByCategoryId = candidates.associate { candidate ->
                candidate.category.id to dao.getExpenseSumForCategoryInRange(
                    candidate.category.id,
                    fromMs,
                    toMs,
                )
            }
            val lines = SavingsReserveHelper.buildLines(candidates, monthlyPlans, spentByCategoryId)

            withContext(Dispatchers.Main) {
                reserveLines = lines
                targetOptions = targets
                adapter.submit(lines, reservePercent)
                bindTargetSpinner(findViewById(R.id.rolloverTargetSpinner), targets, null)
                bindTargetSpinner(
                    findViewById(R.id.rolloverReserveTargetSpinner),
                    targets,
                    SavingsReservePreferences.getReserveCategoryId(this@RolloverActivity),
                )
                val hasItems = lines.isNotEmpty()
                findViewById<TextView>(R.id.rolloverListCount).apply {
                    visibility = if (hasItems) View.VISIBLE else View.GONE
                    text = resources.getQuantityString(R.plurals.rollover_list_count, lines.size, lines.size)
                }
                findViewById<TextView>(R.id.rolloverEmptyText).visibility = if (hasItems) View.GONE else View.VISIBLE
                findViewById<RecyclerView>(R.id.rolloverRecyclerView).visibility =
                    if (hasItems) View.VISIBLE else View.GONE
                findViewById<MaterialButton>(R.id.rolloverTransferButton).isEnabled =
                    hasItems && targets.isNotEmpty()
                refreshReserveTotal()
            }
        }
    }

    private fun bindTargetSpinner(
        spinner: Spinner,
        targets: List<Pair<BudgetCategory, String>>,
        preferredCategoryId: Int?,
    ) {
        spinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            targets.map { it.second },
        )
        if (preferredCategoryId != null && preferredCategoryId > 0) {
            val index = targets.indexOfFirst { it.first.id == preferredCategoryId }.takeIf { it >= 0 } ?: 0
            spinner.setSelection(index, false)
        }
    }

    private fun refreshReserveTotal() {
        val totalView = findViewById<TextView>(R.id.rolloverReserveTotalText)
        val reserveButton = findViewById<MaterialButton>(R.id.rolloverReserveButton)
        val total = SavingsReserveHelper.totalReserve(reserveLines, adapter.selectedIds(), reservePercent)
        val canReserve = total > 0.0 && targetOptions.isNotEmpty()
        totalView.visibility = if (canReserve) View.VISIBLE else View.GONE
        reserveButton.isEnabled = canReserve
        if (canReserve) {
            totalView.text = getString(R.string.rollover_reserve_total, MoneyFormat.formatRub(total))
        }
    }

    private fun transferReserve() {
        if (targetOptions.isEmpty() || reserveLines.isEmpty()) return
        val spinner = findViewById<Spinner>(R.id.rolloverReserveTargetSpinner)
        val target = targetOptions[spinner.selectedItemPosition].first
        val selected = adapter.selectedIds()
        if (selected.isEmpty()) {
            Toast.makeText(this, R.string.rollover_nothing_selected, Toast.LENGTH_SHORT).show()
            return
        }
        val transfers = selected.mapNotNull { id ->
            val line = reserveLines.firstOrNull { it.candidate.category.id == id } ?: return@mapNotNull null
            val amount = SavingsReserveHelper.reserveAmount(line, reservePercent)
            if (amount <= 0.0 || line.candidate.category.id == target.id) return@mapNotNull null
            line.candidate.category.id to amount
        }
        if (transfers.isEmpty()) {
            Toast.makeText(this, R.string.rollover_reserve_nothing, Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch(Dispatchers.IO) {
            var moved = 0
            var movedTotal = 0.0
            for ((fromId, amount) in transfers) {
                val ok = budgetManager.transferSubcategoryBalance(fromId, target.id, amount)
                if (ok) {
                    moved++
                    movedTotal += amount
                }
            }
            SavingsReservePreferences.setReserveCategoryId(this@RolloverActivity, target.id)
            RolloverPreferences.markRolloverDone(this@RolloverActivity)
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    this@RolloverActivity,
                    getString(
                        R.string.rollover_reserve_done,
                        MoneyFormat.formatRub(movedTotal),
                        moved,
                    ),
                    Toast.LENGTH_LONG,
                ).show()
                finish()
            }
        }
    }

    private fun transferSelected() {
        if (targetOptions.isEmpty() || reserveLines.isEmpty()) return
        val spinner = findViewById<Spinner>(R.id.rolloverTargetSpinner)
        val target = targetOptions[spinner.selectedItemPosition].first
        val selected = adapter.selectedIds()
        if (selected.isEmpty()) {
            Toast.makeText(this, R.string.rollover_nothing_selected, Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch(Dispatchers.IO) {
            var moved = 0
            for (id in selected) {
                val candidate = reserveLines.firstOrNull { it.candidate.category.id == id }?.candidate ?: continue
                if (candidate.category.id == target.id) continue
                val ok = budgetManager.transferSubcategoryBalance(
                    candidate.category.id,
                    target.id,
                    MoneyFormat.roundMoney(candidate.balance),
                )
                if (ok) moved++
            }
            RolloverPreferences.markRolloverDone(this@RolloverActivity)
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    this@RolloverActivity,
                    getString(R.string.rollover_done, moved),
                    Toast.LENGTH_LONG,
                ).show()
                finish()
            }
        }
    }

    private fun finishKeeping() {
        RolloverPreferences.markRolloverDone(this)
        Toast.makeText(this, R.string.rollover_kept, Toast.LENGTH_SHORT).show()
        finish()
    }

    private class RolloverAdapter(
        private val onSelectionChanged: () -> Unit,
    ) : RecyclerView.Adapter<RolloverAdapter.Holder>() {
        private var items: List<SavingsReserveHelper.Line> = emptyList()
        private val checked = linkedSetOf<Int>()
        private var percent = SavingsReservePreferences.DEFAULT_PERCENT

        class Holder(v: View) : RecyclerView.ViewHolder(v) {
            val check: CheckBox = v.findViewById(R.id.rolloverCheck)
            val name: TextView = v.findViewById(R.id.rolloverCategoryName)
            val balance: TextView = v.findViewById(R.id.rolloverBalance)
            val reservePreview: TextView = v.findViewById(R.id.rolloverReservePreview)
        }

        fun submit(list: List<SavingsReserveHelper.Line>, percent: Int) {
            items = list
            this.percent = percent
            checked.clear()
            checked.addAll(list.map { it.candidate.category.id })
            notifyDataSetChanged()
            onSelectionChanged()
        }

        fun updatePercent(value: Int) {
            percent = value
            notifyDataSetChanged()
        }

        fun selectedIds(): Set<Int> = checked.toSet()

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_rollover_line, parent, false)
            return Holder(v)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = items[position]
            val ctx = holder.itemView.context
            holder.name.text = item.candidate.label
            holder.balance.text = when (item.basis) {
                SavingsReserveHelper.Basis.ECONOMY -> ctx.getString(
                    R.string.rollover_line_economy,
                    MoneyFormat.formatRub(item.economy),
                    MoneyFormat.formatRub(item.candidate.balance),
                    MoneyFormat.formatRub(item.planned),
                    MoneyFormat.formatRub(item.spent),
                )
                SavingsReserveHelper.Basis.REMAINDER -> ctx.getString(
                    R.string.rollover_line_remainder,
                    MoneyFormat.formatRub(item.candidate.balance),
                )
                SavingsReserveHelper.Basis.NONE -> ctx.getString(
                    R.string.rollover_line_remainder,
                    MoneyFormat.formatRub(item.candidate.balance),
                )
            }
            val reserveAmount = SavingsReserveHelper.reserveAmount(item, percent)
            if (reserveAmount > 0.0) {
                holder.reservePreview.visibility = View.VISIBLE
                val basisLabel = when (item.basis) {
                    SavingsReserveHelper.Basis.ECONOMY -> ctx.getString(R.string.rollover_reserve_basis_economy)
                    SavingsReserveHelper.Basis.REMAINDER -> ctx.getString(R.string.rollover_reserve_basis_remainder)
                    SavingsReserveHelper.Basis.NONE -> ""
                }
                holder.reservePreview.text = ctx.getString(
                    R.string.rollover_line_reserve_preview,
                    MoneyFormat.formatRub(reserveAmount),
                    basisLabel,
                )
            } else {
                holder.reservePreview.visibility = View.GONE
            }
            holder.check.setOnCheckedChangeListener(null)
            holder.check.isChecked = checked.contains(item.candidate.category.id)
            holder.check.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) checked.add(item.candidate.category.id) else checked.remove(item.candidate.category.id)
                onSelectionChanged()
            }
            holder.itemView.setOnClickListener { holder.check.toggle() }
        }

        override fun getItemCount(): Int = items.size
    }
}
