package ru.mybudget.app

import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class DistributionLinesAdapter(
    private val onAmountChanged: (categoryId: Int, amount: Double) -> Unit,
    private val onRemove: (categoryId: Int) -> Unit,
) : RecyclerView.Adapter<DistributionLinesAdapter.Holder>() {
    private var items: List<Line> = emptyList()

    data class Line(
        val categoryId: Int,
        val title: String,
        val balanceHint: String,
        val amountText: String,
    )

    fun submit(newItems: List<Line>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_distribution_line, parent, false)
        return Holder(view)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(items[position])
    }

    inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
        private val name: TextView = view.findViewById(R.id.categoryName)
        private val balance: TextView = view.findViewById(R.id.subBalanceText)
        private val amount: EditText = view.findViewById(R.id.amountInput)
        private val remove: ImageButton = view.findViewById(R.id.removeCategoryButton)
        private var boundId = 0
        private val watcher = object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                if (boundId == 0) return
                onAmountChanged(boundId, MoneyFormat.parse(s) ?: 0.0)
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        }

        init {
            amount.addTextChangedListener(watcher)
        }

        fun bind(line: Line) {
            boundId = 0
            name.text = line.title
            balance.text = line.balanceHint
            if (amount.text.toString() != line.amountText) {
                amount.setText(line.amountText)
                amount.setSelection(amount.text.length)
            }
            boundId = line.categoryId
            remove.setOnClickListener { onRemove(line.categoryId) }
        }
    }
}
