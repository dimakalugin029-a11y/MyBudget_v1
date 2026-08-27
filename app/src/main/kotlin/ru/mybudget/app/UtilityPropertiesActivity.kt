package ru.mybudget.app

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.Spinner
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
import ru.mybudget.app.data.UtilityPropertyEntity
import ru.mybudget.app.setup.ActivePropertyPreferences
import ru.mybudget.app.utilities.UtilityPropertyCopyDialogHelper
import ru.mybudget.app.utilities.UtilityPropertyCopyHelper
import ru.mybudget.app.utilities.UtilityPropertyCopyOptions

class UtilityPropertiesActivity : AppCompatActivity() {
    private lateinit var manager: BudgetManager
    private lateinit var adapter: PropertiesAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_utility_properties)
        manager = BudgetManager.getInstance(this)
        ScreenHeaderHelper.setup(
            this,
            getString(R.string.utility_properties_title),
            getString(R.string.main_icon_utilities),
        )
        adapter = PropertiesAdapter()
        findViewById<RecyclerView>(R.id.utilityPropertiesRecycler).apply {
            layoutManager = LinearLayoutManager(this@UtilityPropertiesActivity)
            this.adapter = this@UtilityPropertiesActivity.adapter
        }
        findViewById<View>(R.id.addUtilityPropertyButton).setOnClickListener { showAddDialog() }
        lifecycleScope.launch(Dispatchers.IO) {
            UtilityPropertyCopyHelper.ensureDefaultProperty(manager.utilityDao)
        }
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun reload() {
        lifecycleScope.launch {
            val properties = withContext(Dispatchers.IO) { manager.utilityDao.getAllProperties() }
            adapter.submit(properties, ActivePropertyPreferences.getActivePropertyId(this@UtilityPropertiesActivity))
        }
    }

    private fun showAddDialog() {
        lifecycleScope.launch {
            val existing = withContext(Dispatchers.IO) { manager.utilityDao.getAllProperties() }
            val nameInput = nameField()
            val container = LinearLayout(this@UtilityPropertiesActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(48, 16, 48, 0)
                addView(nameInput)
            }
            var copySpinner: Spinner? = null
            var copyOptionsView: UtilityPropertyCopyDialogHelper.OptionsView? = null
            if (existing.isNotEmpty()) {
                val copyLabel = TextView(this@UtilityPropertiesActivity).apply {
                    text = getString(R.string.utility_properties_copy_on_create)
                    setPadding(0, 24, 0, 8)
                }
                copySpinner = Spinner(this@UtilityPropertiesActivity).apply {
                    adapter = ArrayAdapter(
                        this@UtilityPropertiesActivity,
                        android.R.layout.simple_spinner_dropdown_item,
                        listOf(getString(R.string.utility_properties_copy_none)) +
                            existing.map { it.name },
                    )
                }
                val optionsLabel = TextView(this@UtilityPropertiesActivity).apply {
                    text = getString(R.string.utility_properties_copy_pick)
                    setPadding(0, 16, 0, 8)
                }
                copyOptionsView = UtilityPropertyCopyDialogHelper.buildOptionsView(
                    this@UtilityPropertiesActivity,
                    UtilityPropertyCopyOptions(template = true, tariffs = true, meters = true),
                )
                container.addView(copyLabel)
                container.addView(copySpinner)
                container.addView(optionsLabel)
                container.addView(copyOptionsView!!.container)
            }
            AlertDialog.Builder(this@UtilityPropertiesActivity)
                .setTitle(R.string.utility_properties_add)
                .setView(container)
                .setPositiveButton(R.string.save) { _, _ ->
                    val name = nameInput.text.toString().trim()
                    if (name.isEmpty()) {
                        Toast.makeText(
                            this@UtilityPropertiesActivity,
                            R.string.utility_properties_name_required,
                            Toast.LENGTH_SHORT,
                        ).show()
                        return@setPositiveButton
                    }
                    lifecycleScope.launch {
                        val copyFromId = copySpinner?.let { spinner ->
                            val index = spinner.selectedItemPosition - 1
                            if (index >= 0) existing[index].id else null
                        }
                        val copyOptions = if (copyFromId != null) {
                            copyOptionsView?.readOptions()?.takeIf { it.hasAny() }
                        } else {
                            null
                        }
                        if (copyFromId != null && copyOptions == null) {
                            Toast.makeText(
                                this@UtilityPropertiesActivity,
                                R.string.utility_properties_copy_nothing,
                                Toast.LENGTH_SHORT,
                            ).show()
                            return@launch
                        }
                        val id = withContext(Dispatchers.IO) {
                            UtilityPropertyCopyHelper.createProperty(
                                manager.utilityDao,
                                name,
                                copyFromId,
                                copyOptions,
                            )
                        }
                        ActivePropertyPreferences.setActivePropertyId(this@UtilityPropertiesActivity, id)
                        Toast.makeText(
                            this@UtilityPropertiesActivity,
                            R.string.utility_properties_created,
                            Toast.LENGTH_SHORT,
                        ).show()
                        reload()
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun showRenameDialog(property: UtilityPropertyEntity) {
        val input = nameField(property.name)
        AlertDialog.Builder(this)
            .setTitle(R.string.utility_properties_rename)
            .setView(input)
            .setPositiveButton(R.string.save) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) return@setPositiveButton
                lifecycleScope.launch(Dispatchers.IO) {
                    manager.utilityDao.updateProperty(property.copy(name = name))
                    withContext(Dispatchers.Main) { reload() }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showCopyFromDialog(target: UtilityPropertyEntity) {
        lifecycleScope.launch {
            val sources = withContext(Dispatchers.IO) {
                manager.utilityDao.getAllProperties().filter { it.id != target.id }
            }
            if (sources.isEmpty()) {
                Toast.makeText(
                    this@UtilityPropertiesActivity,
                    R.string.utility_properties_copy_no_source,
                    Toast.LENGTH_SHORT,
                ).show()
                return@launch
            }
            val labels = sources.map { it.name }.toTypedArray()
            var selectedIndex = 0
            AlertDialog.Builder(this@UtilityPropertiesActivity)
                .setTitle(getString(R.string.utility_properties_copy_title, target.name))
                .setSingleChoiceItems(labels, 0) { _, which -> selectedIndex = which }
                .setPositiveButton(R.string.utility_properties_copy_confirm_button) { _, _ ->
                    val source = sources[selectedIndex]
                    confirmCopyFrom(source, target)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun confirmCopyFrom(
        source: UtilityPropertyEntity,
        target: UtilityPropertyEntity,
    ) {
        val optionsView = UtilityPropertyCopyDialogHelper.buildOptionsView(
            this,
            UtilityPropertyCopyOptions(template = true, tariffs = true, meters = false),
        )
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 16, 48, 8)
            addView(
                TextView(this@UtilityPropertiesActivity).apply {
                    text = getString(R.string.utility_properties_copy_confirm, source.name, target.name)
                },
            )
            addView(
                TextView(this@UtilityPropertiesActivity).apply {
                    text = getString(R.string.utility_properties_copy_pick)
                    setPadding(0, 16, 0, 8)
                },
            )
            addView(optionsView.container)
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.utility_properties_copy_title, target.name))
            .setView(container)
            .setPositiveButton(R.string.utility_properties_copy_confirm_button) { _, _ ->
                val options = optionsView.readOptions()
                if (!options.hasAny()) {
                    Toast.makeText(this, R.string.utility_properties_copy_nothing, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        UtilityPropertyCopyHelper.copyPropertyData(
                            manager.utilityDao,
                            source.id,
                            target.id,
                            options,
                        )
                    }
                    Toast.makeText(
                        this@UtilityPropertiesActivity,
                        R.string.utility_properties_copy_done,
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmDelete(property: UtilityPropertyEntity) {
        AlertDialog.Builder(this)
            .setTitle(R.string.utility_properties_delete)
            .setMessage(getString(R.string.utility_properties_delete_msg, property.name))
            .setPositiveButton(R.string.utility_properties_delete) { _, _ ->
                lifecycleScope.launch {
                    val ok = withContext(Dispatchers.IO) {
                        UtilityPropertyCopyHelper.deleteProperty(manager.utilityDao, property.id)
                    }
                    if (!ok) {
                        Toast.makeText(
                            this@UtilityPropertiesActivity,
                            R.string.utility_properties_delete_failed,
                            Toast.LENGTH_SHORT,
                        ).show()
                        return@launch
                    }
                    if (ActivePropertyPreferences.getActivePropertyId(this@UtilityPropertiesActivity) == property.id) {
                        val fallback = withContext(Dispatchers.IO) {
                            manager.utilityDao.getAllProperties().firstOrNull()?.id
                                ?: ActivePropertyPreferences.DEFAULT_PROPERTY_ID
                        }
                        ActivePropertyPreferences.setActivePropertyId(this@UtilityPropertiesActivity, fallback)
                    }
                    Toast.makeText(
                        this@UtilityPropertiesActivity,
                        R.string.utility_properties_deleted,
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
            hint = getString(R.string.utility_properties_name_hint)
            setText(value)
            setSelection(text.length)
        }
    }

    private inner class PropertiesAdapter : RecyclerView.Adapter<PropertiesAdapter.Holder>() {
        private var items: List<UtilityPropertyEntity> = emptyList()
        private var activeId = ActivePropertyPreferences.DEFAULT_PROPERTY_ID

        fun submit(newItems: List<UtilityPropertyEntity>, active: Int) {
            items = newItems
            activeId = active
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_utility_property, parent, false)
            return Holder(view)
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val property = items[position]
            holder.name.text = property.name
            holder.badge.visibility = if (property.id == activeId) View.VISIBLE else View.GONE
            holder.itemView.setOnClickListener {
                ActivePropertyPreferences.setActivePropertyId(this@UtilityPropertiesActivity, property.id)
                Toast.makeText(
                    this@UtilityPropertiesActivity,
                    R.string.utility_properties_selected,
                    Toast.LENGTH_SHORT,
                ).show()
                reload()
            }
            holder.menu.setOnClickListener { view ->
                PopupMenu(view.context, view).apply {
                    menu.add(0, 1, 0, R.string.utility_properties_use)
                    menu.add(0, 2, 0, R.string.utility_properties_rename)
                    menu.add(0, 3, 0, R.string.utility_properties_copy_structure)
                    menu.add(0, 4, 0, R.string.utility_properties_delete)
                    setOnMenuItemClickListener { item ->
                        when (item.itemId) {
                            1 -> {
                                ActivePropertyPreferences.setActivePropertyId(
                                    this@UtilityPropertiesActivity,
                                    property.id,
                                )
                                reload()
                            }
                            2 -> showRenameDialog(property)
                            3 -> showCopyFromDialog(property)
                            4 -> confirmDelete(property)
                        }
                        true
                    }
                    show()
                }
            }
        }

        inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val name: TextView = view.findViewById(R.id.utilityPropertyName)
            val badge: TextView = view.findViewById(R.id.utilityPropertyActiveBadge)
            val menu: ImageButton = view.findViewById(R.id.utilityPropertyMenuButton)
        }
    }
}
