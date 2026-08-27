package ru.mybudget.app.utilities

import android.content.Context
import android.graphics.Typeface
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import ru.mybudget.app.R
import ru.mybudget.app.ScreenHintPreferences
import ru.mybudget.app.data.UtilityDao
import ru.mybudget.app.setup.UtilitySetupPreferences
import kotlin.math.min

data class UtilitySetupState(
    val hasTemplate: Boolean,
    val tariffLinesCount: Int,
    val tariffsFilledCount: Int,
    val hasMonths: Boolean,
) {
    val templateDone: Boolean get() = hasTemplate
    val tariffsDone: Boolean get() = tariffLinesCount == 0 || tariffsFilledCount >= tariffLinesCount
    val monthsDone: Boolean get() = hasMonths
    val allDone: Boolean get() = templateDone && tariffsDone && monthsDone
    val currentStep: Int
        get() = when {
            !templateDone -> 1
            !tariffsDone -> 2
            !monthsDone -> 3
            else -> 0
        }

    companion object {
        suspend fun load(dao: UtilityDao, propertyId: Int): UtilitySetupState {
            val sectionCount = dao.getTemplateSectionCount(propertyId)
            val tariffLines = dao.getTemplateTariffLineCount(propertyId)
            val filled = dao.getFilledTariffCount(propertyId)
            val billCount = dao.getAllBills(propertyId).size
            return UtilitySetupState(
                hasTemplate = sectionCount > 0,
                tariffLinesCount = tariffLines,
                tariffsFilledCount = min(filled, tariffLines),
                hasMonths = billCount > 0,
            )
        }
    }
}

object UtilitySetupGuideHelper {
    const val PREF_KEY = "hint_utilities_setup"

    fun bind(activity: AppCompatActivity, guideRoot: View, state: UtilitySetupState) {
        if (UtilitySetupPreferences.isGuideDismissed(activity)) {
            ScreenHintPreferences.dismiss(activity, PREF_KEY)
        }
        if (ScreenHintPreferences.isDismissed(activity, PREF_KEY)) {
            guideRoot.visibility = View.GONE
            return
        }
        guideRoot.visibility = View.VISIBLE
        guideRoot.findViewById<TextView>(R.id.utilitySetupTitle)?.setText(R.string.utility_setup_title)
        bindStep(guideRoot.findViewById(R.id.utilitySetupStep1), activity, 1, state, R.string.utility_setup_step1)
        bindStep(guideRoot.findViewById(R.id.utilitySetupStep2), activity, 2, state, R.string.utility_setup_step2)
        bindStep(guideRoot.findViewById(R.id.utilitySetupStep3), activity, 3, state, R.string.utility_setup_step3)
        val note = guideRoot.findViewById<TextView>(R.id.utilitySetupNote)
        if (note != null) {
            when {
                state.allDone -> {
                    note.visibility = View.VISIBLE
                    note.setText(R.string.utility_setup_all_done)
                }
                state.currentStep == 2 && state.tariffLinesCount == 0 -> {
                    note.visibility = View.VISIBLE
                    note.setText(R.string.utility_setup_tariffs_skip)
                }
                else -> note.visibility = View.GONE
            }
        }
        guideRoot.findViewById<View>(R.id.utilitySetupDismiss)?.setOnClickListener {
            ScreenHintPreferences.dismiss(activity, PREF_KEY)
            UtilitySetupPreferences.dismissGuide(activity)
            guideRoot.visibility = View.GONE
        }
    }

    private fun bindStep(
        view: TextView?,
        context: Context,
        step: Int,
        state: UtilitySetupState,
        textRes: Int,
    ) {
        if (view == null) return
        val done = when (step) {
            1 -> state.templateDone
            2 -> state.tariffsDone
            else -> state.monthsDone
        }
        val marker = when {
            done -> "✓"
            step == state.currentStep -> "→"
            else -> "○"
        }
        view.text = "$marker $step. ${context.getString(textRes)}"
        view.alpha = if (done) 0.75f else 1.0f
        val style = if (step != state.currentStep || state.allDone) Typeface.NORMAL else Typeface.BOLD
        view.setTypeface(view.typeface, style)
    }
}
