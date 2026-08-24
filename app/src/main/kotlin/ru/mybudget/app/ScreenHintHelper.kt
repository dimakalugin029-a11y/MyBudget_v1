package ru.mybudget.app

import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

object ScreenHintPreferences {
    private const val PREFS_NAME = "screen_hints"

    fun isDismissed(context: Context, key: String): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(key, false)
    }

    fun dismiss(context: Context, key: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(key, true)
            .apply()
    }
}

object ScreenHintHelper {
    object Keys {
        const val BUDGET = "hint_budget"
        const val INCOME = "hint_income"
        const val EXPENSE = "hint_expense"
        const val INCOME_DISTRIBUTION = "hint_income_distribution"
        const val EXPENSE_DISTRIBUTION = "hint_expense_distribution"
        const val TRANSACTIONS = "hint_transactions"
        const val STATISTICS = "hint_statistics"
        const val GOALS = "hint_goals"
        const val REMINDERS = "hint_reminders"
        const val UTILITY_TEMPLATE = "hint_utility_template"
        const val UTILITY_TARIFFS = "hint_utility_tariffs"
        const val UTILITY_METERS_EXCEL = "hint_utility_meters_excel"
        const val UTILITIES_SETUP = "hint_utilities_setup"
    }

    fun bind(
        activity: AppCompatActivity,
        hintRoot: View,
        prefKey: String,
        textRes: Int,
        showHelpLink: Boolean = true,
    ) {
        hintRoot.findViewById<TextView>(R.id.screenHintText)?.setText(textRes)
        val helpLink = hintRoot.findViewById<TextView>(R.id.screenHintHelp)
        if (showHelpLink) {
            helpLink?.visibility = View.VISIBLE
            helpLink?.setOnClickListener {
                activity.startActivity(Intent(activity, HelpActivity::class.java))
            }
        } else {
            helpLink?.visibility = View.GONE
        }
        if (ScreenHintPreferences.isDismissed(activity, prefKey)) {
            hintRoot.visibility = View.GONE
            return
        }
        hintRoot.visibility = View.VISIBLE
        hintRoot.findViewById<View>(R.id.screenHintDismiss)?.setOnClickListener {
            ScreenHintPreferences.dismiss(activity, prefKey)
            hintRoot.visibility = View.GONE
        }
    }
}
