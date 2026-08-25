package ru.mybudget.app

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch
import ru.mybudget.app.data.BudgetProfileEntity
import ru.mybudget.app.setup.ActiveBudgetPreferences

class BudgetProfilesActivity : AppCompatActivity() {
    private lateinit var manager: BudgetManager
    private lateinit var adapter: ProfilesAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_budget_profiles)
        manager = BudgetManager.getInstance(this)
        ScreenHeaderHelper.setup(this, getString(R.string.budget_profiles_title), getString(R.string.main_profiles_emoji))
        adapter = ProfilesAdapter()
        findViewById<RecyclerView>(R.id.budgetProfilesRecycler).apply {
            layoutManager = LinearLayoutManager(this@BudgetProfilesActivity)
            this.adapter = this@BudgetProfilesActivity.adapter
        }
        findViewById<View>(R.id.addBudgetProfileButton).setOnClickListener { showAddDialog() }
        findViewById<View>(R.id.transferBetweenBudgetsButton).setOnClickListener {
            BudgetTransferDialog.show(this, manager) { reload() }
        }
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun reload() {
        lifecycleScope.launch {
            manager.getCategoriesAsync(forceReload = true)
            adapter.submit(manager.getBudgetProfileTotals(), manager.getActiveBudgetId())
        }
    }

    private fun showAddDialog() {
        val input = nameField()
        AlertDialog.Builder(this)
            .setTitle(R.string.budget_profiles_add)
            .setView(input)
            .setPositiveButton(R.string.save) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) {
                    Toast.makeText(this, R.string.budget_profiles_name_required, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                lifecycleScope.launch {
                    val id = manager.createBudgetProfile(name)
                    manager.setActiveBudgetId(id)
                    Toast.makeText(this@BudgetProfilesActivity, R.string.budget_profiles_created, Toast.LENGTH_SHORT).show()
                    reload()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showRenameDialog(profile: BudgetProfileEntity) {
        val input = nameField(profile.name)
        AlertDialog.Builder(this)
            .setTitle(R.string.budget_profiles_rename)
            .setView(input)
            .setPositiveButton(R.string.save) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) return@setPositiveButton
                lifecycleScope.launch {
                    manager.renameBudgetProfile(profile.id, name)
                    reload()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmDelete(profile: BudgetProfileEntity) {
        AlertDialog.Builder(this)
            .setTitle(R.string.budget_profiles_delete)
            .setMessage(getString(R.string.budget_profiles_delete_msg, profile.name))
            .setPositiveButton(R.string.budget_profiles_delete) { _, _ ->
                lifecycleScope.launch {
                    val ok = manager.deleteBudgetProfile(profile.id)
                    Toast.makeText(
                        this@BudgetProfilesActivity,
                        if (ok) R.string.budget_profiles_deleted else R.string.budget_profiles_delete_failed,
                        Toast.LENGTH_SHORT,
                    ).show()
                    reload()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun nameField(value: String = ""): EditText {
        return EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            hint = getString(R.string.budget_profiles_name_hint)
            setText(value)
            setSelection(text.length)
            setPadding(48, 32, 48, 32)
        }
    }

    private inner class ProfilesAdapter : RecyclerView.Adapter<ProfilesAdapter.Holder>() {
        private var items: List<Pair<BudgetProfileEntity, Double>> = emptyList()
        private var activeId = ActiveBudgetPreferences.DEFAULT_BUDGET_ID

        fun submit(newItems: List<Pair<BudgetProfileEntity, Double>>, active: Int) {
            items = newItems
            activeId = active
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_budget_profile, parent, false)
            return Holder(view)
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val (profile, total) = items[position]
            holder.name.text = profile.name
            holder.balance.text = MoneyFormat.formatRub(total)
            holder.badge.visibility = if (profile.id == activeId) View.VISIBLE else View.GONE
            holder.itemView.setOnClickListener {
                manager.setActiveBudgetId(profile.id)
                Toast.makeText(this@BudgetProfilesActivity, R.string.budget_profiles_selected, Toast.LENGTH_SHORT).show()
                reload()
            }
            holder.menu.setOnClickListener { view ->
                PopupMenu(view.context, view).apply {
                    menu.add(0, 1, 0, R.string.budget_profiles_use)
                    menu.add(0, 2, 0, R.string.budget_profiles_rename)
                    menu.add(0, 3, 0, R.string.budget_profiles_open_budget)
                    menu.add(0, 4, 0, R.string.budget_profiles_delete)
                    setOnMenuItemClickListener { item ->
                        when (item.itemId) {
                            1 -> {
                                manager.setActiveBudgetId(profile.id)
                                reload()
                            }
                            2 -> showRenameDialog(profile)
                            3 -> {
                                manager.setActiveBudgetId(profile.id)
                                startActivity(Intent(this@BudgetProfilesActivity, BudgetActivity::class.java))
                            }
                            4 -> confirmDelete(profile)
                        }
                        true
                    }
                    show()
                }
            }
        }

        inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val name: TextView = view.findViewById(R.id.budgetProfileName)
            val balance: TextView = view.findViewById(R.id.budgetProfileBalance)
            val badge: TextView = view.findViewById(R.id.budgetProfileActiveBadge)
            val menu: ImageButton = view.findViewById(R.id.budgetProfileMenuButton)
        }
    }
}
