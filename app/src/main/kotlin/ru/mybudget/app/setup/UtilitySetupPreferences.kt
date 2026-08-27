package ru.mybudget.app.setup

import android.content.Context

object UtilitySetupPreferences {
    private const val PREFS_NAME = "utility_setup"
    private const val KEY_INTRO_SHOWN = "intro_shown"
    private const val KEY_GUIDE_DISMISSED = "guide_dismissed"
    private const val KEY_PAY_PRIMARY_PREFIX = "pay_primary_property_"
    private const val KEY_PAY_EXTRA_PREFIX = "pay_extra_property_"
    private const val KEY_LEGACY_PAY_PRIMARY_PREFIX = "pay_primary_category_id_"
    private const val KEY_LEGACY_PAY_EXTRA_PREFIX = "pay_extra_category_id_"

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

    fun getPayPrimaryCategoryId(context: Context, propertyId: Int): Int {
        return readCategoryId(context, propertyId, KEY_PAY_PRIMARY_PREFIX, KEY_LEGACY_PAY_PRIMARY_PREFIX)
    }

    fun setPayPrimaryCategoryId(context: Context, categoryId: Int, propertyId: Int) {
        prefs(context).edit().putInt(KEY_PAY_PRIMARY_PREFIX + propertyId, categoryId).apply()
    }

    fun getPayExtraCategoryId(context: Context, propertyId: Int): Int {
        return readCategoryId(context, propertyId, KEY_PAY_EXTRA_PREFIX, KEY_LEGACY_PAY_EXTRA_PREFIX)
    }

    fun setPayExtraCategoryId(context: Context, categoryId: Int, propertyId: Int) {
        prefs(context).edit().putInt(KEY_PAY_EXTRA_PREFIX + propertyId, categoryId).apply()
    }

    private fun readCategoryId(
        context: Context,
        propertyId: Int,
        propertyPrefix: String,
        legacyPrefix: String,
    ): Int {
        val store = prefs(context)
        val key = propertyPrefix + propertyId
        if (store.contains(key)) return store.getInt(key, 0)
        if (propertyId == ActivePropertyPreferences.DEFAULT_PROPERTY_ID) {
            val budgetId = ActiveBudgetPreferences.getActiveBudgetId(context)
            val legacy = store.getInt(legacyPrefix + budgetId, 0)
            if (legacy != 0) {
                store.edit().putInt(key, legacy).apply()
            }
            return legacy
        }
        return 0
    }
}
