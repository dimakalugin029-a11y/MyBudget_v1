package ru.mybudget.app

import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.button.MaterialButton

/** This dialog edits a draft only; it never writes transactions. */
object OverspendCoverageDialog {
    class Draft {
        val selected = mutableSetOf<Int>()
        val raw = mutableMapOf<Int, String>()
        var open = false
        fun save(out: Bundle) {
            out.putBoolean("coverage_open", open)
            out.putIntArray("coverage_selected", selected.toIntArray())
            out.putIntArray("coverage_ids", raw.keys.toIntArray())
            out.putStringArray("coverage_raw", raw.values.toTypedArray())
        }
        fun restore(state: Bundle?) {
            open = state?.getBoolean("coverage_open") ?: false
            selected.addAll(state?.getIntArray("coverage_selected")?.toList().orEmpty())
            val ids = state?.getIntArray("coverage_ids") ?: return
            val values = state.getStringArray("coverage_raw").orEmpty()
            ids.forEachIndexed { index, id -> values.getOrNull(index)?.let { raw[id] = it } }
        }
    }

    fun show(activity: AppCompatActivity, categories: List<BudgetCategory>, parents: Map<Int, String>,
        income: Double, other: Double, draft: Draft, onClose: () -> Unit, onApply: (Map<Int, Double>) -> Unit) {
        val density = activity.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val root = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), 0, dp(20), 0) }
        val body = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        body.addView(TextView(activity).apply { setText(R.string.overspend_explanation); textSize = 14f })
        val scroll = ScrollView(activity).apply { isFillViewport = false; addView(body) }
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            minOf(dp(110 + categories.size * 170), (activity.resources.displayMetrics.heightPixels * 0.43).toInt()), 1f))
        val summary = TextView(activity).apply { textSize = 14f; setPadding(0, dp(12), 0, dp(8)); accessibilityLiveRegion = android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE }
        root.addView(summary)
        val fill = MaterialButton(activity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply { setText(R.string.overspend_fill) }
        root.addView(fill, LinearLayout.LayoutParams(-1, -2))
        val inputs = mutableMapOf<Int, EditText>()
        val previews = mutableMapOf<Int, TextView>()
        fun amount(id: Int): Double? = draft.raw[id].orEmpty().let { if (it.isBlank()) 0.0 else MoneyFormat.parse(it) }
        fun update() {
            var coverage = 0.0
            categories.forEach { category ->
                val value = if (category.id in draft.selected) amount(category.id) else 0.0
                if (value != null && value.isFinite() && value >= 0.0) coverage += MoneyFormat.roundMoney(value)
                previews[category.id]?.text = activity.getString(R.string.overspend_preview,
                    MoneyFormat.formatRub(category.currentBalance), MoneyFormat.formatRub(value?.takeIf { it.isFinite() } ?: 0.0),
                    MoneyFormat.formatRub(category.currentBalance + (value?.takeIf { it.isFinite() } ?: 0.0)))
            }
            summary.text = activity.getString(R.string.overspend_summary, MoneyFormat.formatRub(income),
                MoneyFormat.formatRub(other), MoneyFormat.formatRub(coverage), MoneyFormat.formatRub(MoneyFormat.roundMoney(income - other - coverage)))
        }
        categories.forEach { category ->
            val check = CheckBox(activity).apply {
                text = CategoryMultiPicker.leafLabel(category, parents)
                minHeight = dp(48); isChecked = category.id in draft.selected
            }
            body.addView(check)
            val preview = TextView(activity).apply { textSize = 14f }
            previews[category.id] = preview; body.addView(preview)
            val input = EditText(activity).apply {
                setBackgroundResource(R.drawable.operation_input_bg)
                setTextColor(androidx.core.content.ContextCompat.getColor(activity, R.color.text_primary))
                setHintTextColor(androidx.core.content.ContextCompat.getColor(activity, R.color.text_secondary))
                textSize = 16f; minHeight = dp(56); setPadding(dp(12), dp(12), dp(12), dp(12))
                setHint(R.string.overspend_amount)
                contentDescription = activity.getString(R.string.operation_amount_for_category, check.text)
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                isSingleLine = true; imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
                setText(draft.raw[category.id].orEmpty()); isEnabled = check.isChecked
                doAfterTextChanged { draft.raw[category.id] = it.toString(); error = null; update() }
            }
            inputs[category.id] = input
            body.addView(input, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
            check.setOnCheckedChangeListener { _, checked ->
                if (checked) draft.selected.add(category.id) else draft.selected.remove(category.id)
                input.isEnabled = checked; input.error = null; update()
            }
        }
        fill.setOnClickListener {
            val selected = categories.filter { it.id in draft.selected }
            when {
                selected.isEmpty() -> Toast.makeText(activity, R.string.overspend_select, Toast.LENGTH_LONG).show()
                !OverspendCoverage.fits(income, other, selected.sumOf { OverspendCoverage.deficit(it) }) ->
                    Toast.makeText(activity, R.string.overspend_insufficient, Toast.LENGTH_LONG).show()
                else -> selected.forEach { inputs[it.id]?.setText(MoneyFormat.format(OverspendCoverage.deficit(it))) }
            }
        }
        draft.open = true
        val dialog = AlertDialog.Builder(activity).setTitle(R.string.income_cover_overspend).setView(root)
            .setPositiveButton(R.string.overspend_apply, null).setNegativeButton(android.R.string.cancel, null).create()
        dialog.setOnDismissListener { draft.open = false; onClose() }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val allocation = linkedMapOf<Int, Double>()
                var invalid = false
                categories.filter { it.id in draft.selected }.forEach { category ->
                    val value = amount(category.id)
                    if (!OverspendCoverage.valid(value, OverspendCoverage.deficit(category))) {
                        inputs[category.id]?.error = activity.getString(R.string.overspend_invalid); invalid = true
                    } else if (value != null && MoneyFormat.roundMoney(value) > 0.0) allocation[category.id] = MoneyFormat.roundMoney(value)
                }
                if (invalid) return@setOnClickListener
                if (allocation.isEmpty()) { Toast.makeText(activity, R.string.overspend_select, Toast.LENGTH_LONG).show(); return@setOnClickListener }
                if (!OverspendCoverage.fits(income, other, allocation.values.sum())) {
                    Toast.makeText(activity, R.string.overspend_insufficient, Toast.LENGTH_LONG).show(); return@setOnClickListener
                }
                onApply(allocation); dialog.dismiss()
            }
        }
        update(); dialog.show()
        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    }
}
