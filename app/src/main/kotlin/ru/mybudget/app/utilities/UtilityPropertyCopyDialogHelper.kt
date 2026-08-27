package ru.mybudget.app.utilities

import android.content.Context
import android.widget.CheckBox
import android.widget.LinearLayout
import ru.mybudget.app.R

object UtilityPropertyCopyDialogHelper {
    data class OptionsView(
        val container: LinearLayout,
        val readOptions: () -> UtilityPropertyCopyOptions,
    )

    fun buildOptionsView(
        context: Context,
        defaults: UtilityPropertyCopyOptions = UtilityPropertyCopyOptions(
            template = true,
            tariffs = true,
            meters = false,
        ),
    ): OptionsView {
        val templateCheck = CheckBox(context).apply {
            text = context.getString(R.string.utility_properties_copy_template)
            isChecked = defaults.template
        }
        val tariffsCheck = CheckBox(context).apply {
            text = context.getString(R.string.utility_properties_copy_tariffs)
            isChecked = defaults.tariffs
        }
        val metersCheck = CheckBox(context).apply {
            text = context.getString(R.string.utility_properties_copy_meters)
            isChecked = defaults.meters
        }
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 16, 48, 8)
            addView(templateCheck)
            addView(tariffsCheck)
            addView(metersCheck)
        }
        return OptionsView(container) {
            UtilityPropertyCopyOptions(
                template = templateCheck.isChecked,
                tariffs = tariffsCheck.isChecked,
                meters = metersCheck.isChecked,
            )
        }
    }
}
