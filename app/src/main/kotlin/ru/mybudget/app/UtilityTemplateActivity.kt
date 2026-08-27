package ru.mybudget.app

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
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
import ru.mybudget.app.data.UtilityTemplateLineEntity
import ru.mybudget.app.data.UtilityTemplateSectionEntity
import ru.mybudget.app.data.UtilityTemplateSectionWithLines
import ru.mybudget.app.setup.ActivePropertyPreferences
import ru.mybudget.app.utilities.UtilityPropertyCopyDialogHelper
import ru.mybudget.app.utilities.UtilityPropertyCopyHelper
import ru.mybudget.app.utilities.UtilityPropertyCopyOptions
import ru.mybudget.app.utilities.UtilityUserTemplate

class UtilityTemplateActivity : AppCompatActivity() {
    private lateinit var adapter: TemplateAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_utility_template)
        ScreenHeaderHelper.setup(this, getString(R.string.utility_template_title), getString(R.string.settings_template_emoji))
        findViewById<View>(R.id.utilityTemplateHint)?.let {
            ScreenHintHelper.bind(
                this,
                it,
                ScreenHintHelper.Keys.UTILITY_TEMPLATE,
                R.string.hint_utility_template,
                showHelpLink = false,
            )
        }
        adapter = TemplateAdapter(
            onEditSection = { showEditSectionDialog(it) },
            onDeleteSection = { confirmDeleteSection(it) },
            onAddLine = { showLineDialog(it, null) },
            onEditLine = { sectionId, line -> showLineDialog(sectionId, line) },
            onDeleteLine = { confirmDeleteLine(it) },
        )
        findViewById<RecyclerView>(R.id.templateRecycler).apply {
            layoutManager = LinearLayoutManager(this@UtilityTemplateActivity)
            this.adapter = this@UtilityTemplateActivity.adapter
        }
        findViewById<View>(R.id.addTemplateSectionButton).setOnClickListener { showAddSectionDialog() }
        findViewById<View>(R.id.copyTemplateFromPropertyButton).setOnClickListener { showCopyFromPropertyDialog() }
        loadTemplate()
    }

    override fun onResume() {
        super.onResume()
        loadTemplate()
    }

    private fun dao() = BudgetManager.getInstance(this).utilityDao

    private fun propertyId() = ActivePropertyPreferences.getActivePropertyId(this)

    private fun loadTemplate() {
        lifecycleScope.launch {
            val sections = withContext(Dispatchers.IO) {
                UtilityUserTemplate.getTemplateWithLines(dao(), propertyId())
            }
            adapter.submit(sections)
            val empty = sections.isEmpty()
            findViewById<View>(R.id.templateEmpty).visibility = if (empty) View.VISIBLE else View.GONE
            findViewById<View>(R.id.templateRecycler).visibility = if (empty) View.GONE else View.VISIBLE
        }
    }

    private fun showCopyFromPropertyDialog() {
        lifecycleScope.launch {
            val sources = withContext(Dispatchers.IO) {
                dao().getAllProperties().filter { it.id != propertyId() }
            }
            if (sources.isEmpty()) {
                Toast.makeText(
                    this@UtilityTemplateActivity,
                    R.string.utility_properties_copy_no_source,
                    Toast.LENGTH_SHORT,
                ).show()
                return@launch
            }
            val labels = sources.map { it.name }.toTypedArray()
            var selectedIndex = 0
            AlertDialog.Builder(this@UtilityTemplateActivity)
                .setTitle(R.string.utility_template_copy_from)
                .setSingleChoiceItems(labels, 0) { _, which -> selectedIndex = which }
                .setPositiveButton(R.string.utility_properties_copy_confirm_button) { _, _ ->
                    val source = sources[selectedIndex]
                    showCopyOptionsDialog(source)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun showCopyOptionsDialog(source: ru.mybudget.app.data.UtilityPropertyEntity) {
        val targetId = propertyId()
        val optionsView = UtilityPropertyCopyDialogHelper.buildOptionsView(
            this,
            UtilityPropertyCopyOptions(template = true, tariffs = true, meters = false),
        )
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 16, 48, 8)
            addView(
                TextView(this@UtilityTemplateActivity).apply {
                    text = getString(R.string.utility_template_copy_from_source, source.name)
                },
            )
            addView(
                TextView(this@UtilityTemplateActivity).apply {
                    text = getString(R.string.utility_properties_copy_pick)
                    setPadding(0, 16, 0, 8)
                },
            )
            addView(optionsView.container)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.utility_template_copy_from)
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
                            dao(),
                            source.id,
                            targetId,
                            options,
                        )
                    }
                    Toast.makeText(
                        this@UtilityTemplateActivity,
                        R.string.utility_properties_copy_done,
                        Toast.LENGTH_SHORT,
                    ).show()
                    loadTemplate()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showAddSectionDialog() {
        val input = EditText(this).apply { hint = getString(R.string.utility_template_section_name_hint) }
        AlertDialog.Builder(this)
            .setTitle(R.string.utility_template_add_section)
            .setView(padded(input))
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) {
                    Toast.makeText(this, R.string.utility_template_name_required, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                lifecycleScope.launch(Dispatchers.IO) {
                    UtilityUserTemplate.addSection(dao(), propertyId(), name)
                    withContext(Dispatchers.Main) { loadTemplate() }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showEditSectionDialog(section: UtilityTemplateSectionEntity) {
        val input = EditText(this).apply { setText(section.name) }
        AlertDialog.Builder(this)
            .setTitle(R.string.utility_template_edit_section)
            .setView(padded(input))
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) return@setPositiveButton
                lifecycleScope.launch(Dispatchers.IO) {
                    dao().updateTemplateSection(section.copy(name = name))
                    withContext(Dispatchers.Main) { loadTemplate() }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmDeleteSection(section: UtilityTemplateSectionEntity) {
        AlertDialog.Builder(this)
            .setTitle(R.string.utility_template_delete_section_title)
            .setMessage(getString(R.string.utility_template_delete_section_msg, section.name))
            .setPositiveButton(R.string.delete) { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    dao().deleteTariffsForSection(section.id)
                    dao().deleteTemplateLinesForSection(section.id)
                    dao().deleteTemplateSection(section.id)
                    withContext(Dispatchers.Main) { loadTemplate() }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showLineDialog(sectionId: Int, existing: UtilityTemplateLineEntity?) {
        val view = layoutInflater.inflate(R.layout.dialog_utility_template_line, null)
        val nameInput = view.findViewById<EditText>(R.id.lineNameInput)
        val groupInput = view.findViewById<EditText>(R.id.lineGroupInput)
        val typeSpinner = view.findViewById<Spinner>(R.id.lineTypeSpinner)
        typeSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            listOf(
                getString(R.string.utility_template_type_qty_tariff),
                getString(R.string.utility_template_type_amount_only),
            ),
        )
        if (existing != null) {
            nameInput.setText(existing.name)
            groupInput.setText(existing.groupLabel)
            typeSpinner.setSelection(
                if (existing.lineMode == UtilityTemplateLineEntity.LINE_MODE_AMOUNT_ONLY) 1 else 0,
            )
        }
        AlertDialog.Builder(this)
            .setTitle(if (existing == null) R.string.utility_template_add_line else R.string.utility_template_edit_line)
            .setView(view)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val name = nameInput.text.toString().trim()
                if (name.isEmpty()) {
                    Toast.makeText(this, R.string.utility_template_name_required, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val mode = if (typeSpinner.selectedItemPosition == 1) {
                    UtilityTemplateLineEntity.LINE_MODE_AMOUNT_ONLY
                } else {
                    UtilityTemplateLineEntity.LINE_MODE_QTY_TARIFF
                }
                val group = groupInput.text.toString().trim()
                lifecycleScope.launch(Dispatchers.IO) {
                    if (existing == null) {
                        UtilityUserTemplate.addLine(dao(), sectionId, name, group, mode)
                    } else {
                        dao().updateTemplateLine(existing.copy(name = name, groupLabel = group, lineMode = mode))
                    }
                    withContext(Dispatchers.Main) { loadTemplate() }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmDeleteLine(line: UtilityTemplateLineEntity) {
        AlertDialog.Builder(this)
            .setTitle(R.string.utility_template_delete_line_title)
            .setMessage(line.name)
            .setPositiveButton(R.string.delete) { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    dao().deleteTariffForLine(line.id)
                    dao().deleteTemplateLine(line.id)
                    withContext(Dispatchers.Main) { loadTemplate() }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun padded(child: View): View {
        val pad = (24 * resources.displayMetrics.density).toInt()
        return android.widget.FrameLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(child, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    private class TemplateAdapter(
        private val onEditSection: (UtilityTemplateSectionEntity) -> Unit,
        private val onDeleteSection: (UtilityTemplateSectionEntity) -> Unit,
        private val onAddLine: (Int) -> Unit,
        private val onEditLine: (Int, UtilityTemplateLineEntity) -> Unit,
        private val onDeleteLine: (UtilityTemplateLineEntity) -> Unit,
    ) : RecyclerView.Adapter<TemplateAdapter.SectionHolder>() {
        private var items: List<UtilityTemplateSectionWithLines> = emptyList()

        fun submit(list: List<UtilityTemplateSectionWithLines>) {
            items = list
            notifyDataSetChanged()
        }

        class SectionHolder(v: View) : RecyclerView.ViewHolder(v) {
            val name: TextView = v.findViewById(R.id.templateSectionName)
            val addLine: TextView = v.findViewById(R.id.addLineInSectionButton)
            val linesRecycler: RecyclerView = v.findViewById(R.id.templateLinesRecycler)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SectionHolder {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_utility_template_section, parent, false)
            return SectionHolder(v)
        }

        override fun onBindViewHolder(holder: SectionHolder, position: Int) {
            val item = items[position]
            holder.name.text = item.section.name
            holder.itemView.setOnClickListener { onEditSection(item.section) }
            holder.itemView.setOnLongClickListener {
                onDeleteSection(item.section)
                true
            }
            holder.addLine.setOnClickListener { onAddLine(item.section.id) }
            holder.linesRecycler.layoutManager = LinearLayoutManager(holder.itemView.context)
            holder.linesRecycler.adapter = LineAdapter(item.lines, item.section.id, onEditLine, onDeleteLine)
        }

        override fun getItemCount(): Int = items.size
    }

    private class LineAdapter(
        private val lines: List<UtilityTemplateLineEntity>,
        private val sectionId: Int,
        private val onEditLine: (Int, UtilityTemplateLineEntity) -> Unit,
        private val onDeleteLine: (UtilityTemplateLineEntity) -> Unit,
    ) : RecyclerView.Adapter<LineAdapter.Holder>() {
        class Holder(v: View) : RecyclerView.ViewHolder(v) {
            val name: TextView = v.findViewById(R.id.templateLineName)
            val meta: TextView = v.findViewById(R.id.templateLineMeta)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_utility_template_line, parent, false)
            return Holder(v)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val line = lines[position]
            val ctx = holder.itemView.context
            holder.name.text = line.name
            val mode = ctx.getString(
                if (line.lineMode == UtilityTemplateLineEntity.LINE_MODE_AMOUNT_ONLY) {
                    R.string.utility_template_type_amount_only
                } else {
                    R.string.utility_template_type_qty_tariff
                },
            )
            val group = line.groupLabel.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
            holder.meta.text = mode + group
            holder.itemView.setOnClickListener { onEditLine(sectionId, line) }
            holder.itemView.setOnLongClickListener {
                onDeleteLine(line)
                true
            }
        }

        override fun getItemCount(): Int = lines.size
    }
}
