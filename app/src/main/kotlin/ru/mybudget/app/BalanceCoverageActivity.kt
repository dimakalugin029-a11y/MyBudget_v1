package ru.mybudget.app

import android.content.Intent
import android.os.Bundle
import androidx.core.widget.doAfterTextChanged
import android.view.View
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.Lifecycle
import kotlinx.coroutines.launch

class BalanceCoverageActivity:AppCompatActivity() {
    private val model:BalanceCoverageViewModel by viewModels()
    private var categories=listOf<BudgetCategory>()
    private var targets=listOf<BudgetCategory>()
    private val fields=linkedMapOf<Int,EditText>()
    private lateinit var picker:Spinner
    private lateinit var sources:LinearLayout
    private lateinit var submit:Button
    private lateinit var status:TextView
    private var ready=false
    private var budget=1
    private var restored:Bundle?=null
    override fun onCreate(state:Bundle?) {
        super.onCreate(state)
        setContentView(R.layout.activity_balance_coverage)
        ScreenHeaderHelper.setup(this,getString(R.string.balance_coverage_title))
        restored=state
        budget=state?.getInt("budget") ?: intent.getIntExtra("budgetId",BudgetManager.getInstance(this).getActiveBudgetId())
        picker=findViewById(R.id.coverageTarget);sources=findViewById(R.id.coverageSources)
        submit=findViewById(R.id.coveragePreview);status=findViewById(R.id.coverageStatus)
        findViewById<View>(R.id.coverageOpenBudget).setOnClickListener { if(!model.state.value.working) { startActivity(Intent(this,BudgetActivity::class.java));finish() } }
        onBackPressedDispatcher.addCallback(this,object:OnBackPressedCallback(true) { override fun handleOnBackPressed() { if(!model.state.value.working) finish() } })
        submit.setOnClickListener { preview() }
        lifecycleScope.launch {
            try {
                val manager=BudgetManager.getInstance(this@BalanceCoverageActivity)
                categories=manager.getCategoriesAsync(forceReload=true)
                val leaves=BalanceCoverage.leaves(categories,budget)
                targets=leaves.filter { it.currentBalance<0 }
                picker.adapter=ArrayAdapter(this@BalanceCoverageActivity,R.layout.item_operation_spinner,targets.map { label(it)+" · "+MoneyFormat.formatRub(it.currentBalance) })
                val targetIndex=targets.indexOfFirst { it.id==state?.getInt("target") }.coerceAtLeast(0)
                if(targets.isNotEmpty()) picker.setSelection(targetIndex)
                leaves.filter { it.currentBalance>0 }.groupBy { it.parentId }.forEach { (parentId,group) ->
                    val heading=TextView(this@BalanceCoverageActivity).apply {
                        text=categories.firstOrNull { it.id==parentId }?.name ?: getString(R.string.coverage_standalone_sources)
                        setTextAppearance(R.style.Text_MyBudget_Section)
                        val space=(resources.displayMetrics.density*16).toInt()
                        setPadding(0,space,0,space/4)
                        androidx.core.view.ViewCompat.setAccessibilityHeading(this,true)
                    }
                    sources.addView(heading)
                    group.forEach { category ->
                        val card=layoutInflater.inflate(R.layout.item_balance_coverage_source,sources,false)
                        card.findViewById<TextView>(R.id.coverageSourceName).text=category.name
                        card.findViewById<TextView>(R.id.coverageSourceBalance).text=getString(R.string.coverage_source_balance,MoneyFormat.formatRub(category.currentBalance))
                        val input=card.findViewById<EditText>(R.id.coverageSourceAmount)
                        val after=card.findViewById<TextView>(R.id.coverageSourceAfter)
                        input.contentDescription=getString(R.string.coverage_source_accessibility,label(category))
                        fun updateAfter() {
                            val amount=if(input.text.isBlank()) 0.0 else MoneyFormat.parse(input.text)
                            after.text=if(amount==null || !amount.isFinite() || amount<0 || amount>category.currentBalance)
                                getString(R.string.coverage_source_invalid)
                            else getString(R.string.coverage_source_after,MoneyFormat.formatRub(category.currentBalance-amount))
                        }
                        input.setText(restored?.getString("amount_${category.id}").orEmpty())
                        input.doAfterTextChanged { updateAfter() }
                        updateAfter()
                        fields[category.id]=input
                        sources.addView(card)
                    }
                }
                ready=targets.isNotEmpty() && fields.isNotEmpty()
                status.text=getString(if(targets.isEmpty()) R.string.balance_coverage_no_debt else if(fields.isEmpty()) R.string.balance_coverage_no_money else R.string.balance_coverage_help)
                render()
            } catch(e:kotlinx.coroutines.CancellationException) { throw e
            } catch(_:Exception) { status.setText(R.string.balance_coverage_load_error) }
        }
        lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.RESUMED) {
            model.state.collect { value ->
                render()
                if(value.done) { Toast.makeText(this@BalanceCoverageActivity,R.string.balance_coverage_done,Toast.LENGTH_LONG).show();finish() }
                value.error?.let { Toast.makeText(this@BalanceCoverageActivity,it,Toast.LENGTH_LONG).show();model.clearError() }
            }
        } }
    }
    private fun label(c:BudgetCategory):String {
        val parent=categories.firstOrNull { it.id==c.parentId }?.name
        return if(parent==null) c.name else "$parent → ${c.name}"
    }
    private fun render() {
        val enabled=ready && !model.state.value.working && !model.state.value.done
        submit.isEnabled=enabled;picker.isEnabled=enabled
        fields.values.forEach { it.isEnabled=enabled }
    }
    private fun preview() {
        if(!ready || model.state.value.working || model.state.value.done) return
        try {
            val target=targets[picker.selectedItemPosition]
            val amounts=fields.filterValues { it.text.isNotBlank() }.mapValues { (_,field) ->
                requireNotNull(MoneyFormat.parse(field.text)) { getString(R.string.balance_coverage_invalid) }
            }.filterValues { it!=0.0 }
            val transfers=BalanceCoverage.plan(categories,budget,target.id,amounts)
            val text=transfers.joinToString("\n\n") { t ->
                val from=categories.first { it.id==t.from }
                getString(R.string.balance_coverage_preview_row,label(from),MoneyFormat.formatRub(t.amount),MoneyFormat.formatRub(from.currentBalance-t.amount))
            }+"\n\n"+getString(R.string.balance_coverage_preview_target,label(target),MoneyFormat.formatRub(target.currentBalance+transfers.sumOf { it.amount }))
            val expected=categories.filter { it.id in amounts.keys || it.id==target.id }.associate { it.id to it.currentBalance }
            AlertDialog.Builder(this).setTitle(R.string.balance_coverage_confirm).setMessage(text)
                .setPositiveButton(R.string.balance_coverage_apply) { _,_ -> model.save(budget,target.id,amounts,expected) }
                .setNegativeButton(android.R.string.cancel,null).show()
        } catch(e:Exception) { Toast.makeText(this,e.message.orEmpty(),Toast.LENGTH_LONG).show() }
    }
    override fun onSaveInstanceState(out:Bundle) {
        out.putInt("budget",budget);out.putInt("target",targets.getOrNull(picker.selectedItemPosition)?.id ?: -1)
        fields.forEach { (id,field) -> out.putString("amount_$id",field.text.toString()) }
        super.onSaveInstanceState(out)
    }
}
