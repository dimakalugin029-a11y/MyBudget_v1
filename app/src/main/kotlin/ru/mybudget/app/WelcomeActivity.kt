package ru.mybudget.app

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.mybudget.app.data.BudgetDatabase
import ru.mybudget.app.setup.BudgetTemplateId
import ru.mybudget.app.setup.BudgetTemplateInfo
import ru.mybudget.app.setup.BudgetTemplates
import ru.mybudget.app.setup.SetupPreferences

class WelcomeActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (SetupPreferences.isSetupCompleted(this)) {
            openMainAndFinish()
            return
        }
        lifecycleScope.launch(Dispatchers.IO) {
            val hasData = BudgetDatabase.getInstance(this@WelcomeActivity)
                .budgetDao()
                .getAllCategoriesForExport()
                .isNotEmpty()
            withContext(Dispatchers.Main) {
                if (hasData) {
                    SetupPreferences.markSetupCompleted(this@WelcomeActivity, BudgetTemplateId.CUSTOM)
                    openMainAndFinish()
                } else {
                    showWelcomeScreen()
                }
            }
        }
    }

    private fun showWelcomeScreen() {
        setContentView(R.layout.activity_welcome)
        val recycler = findViewById<RecyclerView>(R.id.welcomeTemplatesRecycler)
        val progress = findViewById<ProgressBar>(R.id.welcomeProgress)
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = TemplateAdapter(BudgetTemplates.all) { template ->
            progress.visibility = View.VISIBLE
            recycler.isEnabled = false
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val db = BudgetDatabase.getInstance(this@WelcomeActivity)
                    BudgetTemplates.apply(db.budgetDao(), db.utilityDao(), template.id, this@WelcomeActivity)
                    BudgetManager.getInstance(this@WelcomeActivity).reloadCategoriesFromDatabase()
                    SetupPreferences.markSetupCompleted(this@WelcomeActivity, template.id)
                    withContext(Dispatchers.Main) {
                        BudgetWidgetProvider.updateAll(this@WelcomeActivity)
                        openMainAndFinish()
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    withContext(Dispatchers.Main) {
                        progress.visibility = View.GONE
                        recycler.isEnabled = true
                        Toast.makeText(
                            this@WelcomeActivity,
                            R.string.welcome_apply_error,
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }
            }
        }
    }

    private fun openMainAndFinish() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private class TemplateAdapter(
        private val items: List<BudgetTemplateInfo>,
        private val onSelect: (BudgetTemplateInfo) -> Unit,
    ) : RecyclerView.Adapter<TemplateAdapter.Holder>() {

        class Holder(v: View) : RecyclerView.ViewHolder(v) {
            val title: TextView = v.findViewById(R.id.templateTitle)
            val description: TextView = v.findViewById(R.id.templateDescription)
            val stats: TextView = v.findViewById(R.id.templateStats)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_welcome_template, parent, false)
            return Holder(v)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = items[position]
            holder.title.text = item.title
            holder.description.text = item.description
            holder.stats.text = if (item.id == BudgetTemplateId.CUSTOM) {
                holder.itemView.context.getString(R.string.welcome_template_custom_stats)
            } else {
                holder.itemView.context.getString(
                    R.string.welcome_template_stats,
                    item.articleCount,
                    item.subcategoryCount,
                )
            }
            holder.itemView.setOnClickListener { onSelect(item) }
        }

        override fun getItemCount(): Int = items.size
    }
}
