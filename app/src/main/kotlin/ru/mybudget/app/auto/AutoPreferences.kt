package ru.mybudget.app.auto

import android.content.Context

object AutoPreferences {
    private const val PREFS = "auto_prefs"

    private fun key(type: String) = "default_category_$type"

    fun getDefaultCategoryId(context: Context, type: String): Int {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(key(type), -1)
    }

    fun setDefaultCategoryId(context: Context, type: String, categoryId: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(key(type), categoryId).apply()
    }
}
