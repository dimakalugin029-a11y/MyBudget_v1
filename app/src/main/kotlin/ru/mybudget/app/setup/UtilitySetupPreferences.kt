package ru.mybudget.app.setup

import android.content.Context

object UtilitySetupPreferences {
    private const val PREFS_NAME = "utility_setup"
    private const val KEY_INTRO_SHOWN = "intro_shown"
    private const val KEY_GUIDE_DISMISSED = "guide_dismissed"
    private const val KEY_PAY_PRIMARY_PREFIX = "pay_primary_category_id_"
    private const val KEY_PAY_EXTRA_PREFIX = "pay_extra_category_id_"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun hasSeenIntro(context: Context): Boolean = prefs(context).getBoolean(KEY_INTRO_SHOWN, false)

    fun markIntroShown(context: Context) {
        prefs(context).edit().putBoolean(KEY_INTRO_SHOWN, true).apply()
    }

    fun isGuideDismissed(context: Context): Boolean =
        prefs(context).getBoolean(KEY_GUIDE_DISMISSED, false)

    fun dismissGuide(context: Context) {
        prefs(context).edit().putBoolean(KEY_GUIDE_DISMISSED, true).apply()
    }

    fun getPayPrimaryCategoryId(context: Context, budgetId: Int): Int {
        return prefs(context).getInt(KEY_PAY_PRIMARY_PREFIX + budgetId, 0)
    }

    fun setPayPrimaryCategoryId(context: Context, categoryId: Int, budgetId: Int) {
        prefs(context).edit().putInt(KEY_PAY_PRIMARY_PREFIX + budgetId, categoryId).apply()
    }

    fun getPayExtraCategoryId(context: Context, budgetId: Int): Int {
        return prefs(context).getInt(KEY_PAY_EXTRA_PREFIX + budgetId, 0)
    }

    fun setPayExtraCategoryId(context: Context, categoryId: Int, budgetId: Int) {
        prefs(context).edit().putInt(KEY_PAY_EXTRA_PREFIX + budgetId, categoryId).apply()
    }
}
