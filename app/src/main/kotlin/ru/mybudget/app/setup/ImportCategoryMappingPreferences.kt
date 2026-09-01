package ru.mybudget.app.setup

import android.content.Context
import ru.mybudget.app.BudgetApplication
import java.util.Locale

object ImportCategoryMappingPreferences {
    private const val KEY_PREFIX = "import_cat_rules_"
    private const val SEP = "\u001e"

    data class Rule(
        val pattern: String,
        val categoryId: Int,
    )

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(BudgetApplication.PREFS_NAME, Context.MODE_PRIVATE)

    fun getRules(context: Context, budgetId: Int): List<Rule> {
        val raw = prefs(context).getString(KEY_PREFIX + budgetId, null) ?: return emptyList()
        return raw.split(SEP).mapNotNull { chunk ->
            val idx = chunk.lastIndexOf(':')
            if (idx <= 0) return@mapNotNull null
            val pattern = chunk.substring(0, idx)
            val categoryId = chunk.substring(idx + 1).toIntOrNull() ?: return@mapNotNull null
            Rule(pattern, categoryId)
        }
    }

    fun remember(context: Context, budgetId: Int, description: String, categoryId: Int) {
        val pattern = normalize(description)
        if (pattern.length < 3) return
        val existing = getRules(context, budgetId).filterNot { it.pattern == pattern }
        val updated = (listOf(Rule(pattern, categoryId)) + existing).take(50)
        prefs(context).edit()
            .putString(KEY_PREFIX + budgetId, updated.joinToString(SEP) { "${it.pattern}:${it.categoryId}" })
            .apply()
    }

    fun ruleFor(description: String, categoryId: Int): Rule? {
        val pattern = normalize(description)
        if (pattern.length < 3) return null
        return Rule(pattern, categoryId)
    }

    fun resolveCategory(description: String, rules: List<Rule>): Int? {
        val normalized = normalize(description)
        if (normalized.isBlank()) return null
        rules.firstOrNull { it.pattern == normalized }?.let { return it.categoryId }
        return rules.firstOrNull { rule ->
            normalized.contains(rule.pattern) || rule.pattern.contains(normalized)
        }?.categoryId
    }

    private fun normalize(description: String): String {
        return description.trim().lowercase(Locale.getDefault()).take(48)
    }
}
