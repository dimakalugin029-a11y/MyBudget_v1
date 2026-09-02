package ru.mybudget.app.setup

enum class BudgetTemplateId {
    SIMPLE,
    MINIMAL,
    EXTENDED,
    FULL,
    CUSTOM,
    ;

    companion object {
        fun fromStoredName(name: String?): BudgetTemplateId? {
            if (name == "PERSONAL") return FULL
            if (name == null) return null
            return runCatching { valueOf(name) }.getOrNull()
        }
    }
}
