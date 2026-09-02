package ru.mybudget.app.setup

import android.content.Context
import ru.mybudget.app.MoneyFormat

data class ExpenseShortcut(
    val label: String,
    val categoryId: Int,
    val amount: Double,
)

object ExpenseShortcutPreferences {
    private const val PREFS_NAME = "expense_shortcuts"
    private const val KEY_DATA = "shortcuts"
    private const val MAX_SHORTCUTS = 6

    fun load(context: Context): List<ExpenseShortcut> {
        val raw = prefs(context).getString(KEY_DATA, null) ?: return emptyList()
        return raw.lines()
            .mapNotNull { line -> decodeLine(line) }
            .take(MAX_SHORTCUTS)
    }

    fun save(context: Context, shortcuts: List<ExpenseShortcut>) {
        val encoded = shortcuts.take(MAX_SHORTCUTS).joinToString("\n") { encodeLine(it) }
        prefs(context).edit().putString(KEY_DATA, encoded).apply()
    }

    fun upsert(context: Context, shortcut: ExpenseShortcut) {
        val current = load(context).toMutableList()
        val index = current.indexOfFirst {
            it.categoryId == shortcut.categoryId && it.label.equals(shortcut.label, ignoreCase = true)
        }
        if (index >= 0) {
            current[index] = shortcut
        } else {
            current.add(0, shortcut)
        }
        save(context, current.distinctBy { it.label.lowercase() }.take(MAX_SHORTCUTS))
    }

    fun remove(context: Context, shortcut: ExpenseShortcut) {
        save(
            context,
            load(context).filterNot {
                it.categoryId == shortcut.categoryId && it.label == shortcut.label
            },
        )
    }

    private fun encodeLine(shortcut: ExpenseShortcut): String {
        return listOf(
            shortcut.label.replace('\t', ' ').replace('\n', ' '),
            shortcut.categoryId.toString(),
            MoneyFormat.roundMoney(shortcut.amount).toString(),
        ).joinToString("\t")
    }

    private fun decodeLine(line: String): ExpenseShortcut? {
        val parts = line.split('\t')
        if (parts.size < 3) return null
        val label = parts[0].trim()
        val categoryId = parts[1].toIntOrNull() ?: return null
        val amount = parts[2].toDoubleOrNull() ?: return null
        if (label.isBlank() || categoryId <= 0 || amount <= 0.0) return null
        return ExpenseShortcut(label = label, categoryId = categoryId, amount = amount)
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
