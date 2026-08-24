package ru.mybudget.app.setup

import android.content.Context
import java.util.Calendar

object MonthStartPreferences {
    private const val PREFS = "month_start_prefs"
    private const val KEY_DONE_YEAR = "done_year"
    private const val KEY_DONE_MONTH = "done_month"

    fun shouldShowWizard(context: Context): Boolean {
        val cal = Calendar.getInstance()
        val year = cal.get(Calendar.YEAR)
        val month = cal.get(Calendar.MONTH) + 1
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val doneYear = prefs.getInt(KEY_DONE_YEAR, -1)
        val doneMonth = prefs.getInt(KEY_DONE_MONTH, -1)
        return !(doneYear == year && doneMonth == month) && cal.get(Calendar.DAY_OF_MONTH) <= 7
    }

    fun markDone(context: Context) {
        val cal = Calendar.getInstance()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_DONE_YEAR, cal.get(Calendar.YEAR))
            .putInt(KEY_DONE_MONTH, cal.get(Calendar.MONTH) + 1)
            .apply()
    }
}
