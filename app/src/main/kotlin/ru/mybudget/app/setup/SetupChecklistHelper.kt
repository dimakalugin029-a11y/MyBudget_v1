package ru.mybudget.app.setup

import ru.mybudget.app.data.TransactionEntity

object SetupChecklistHelper {
    enum class StepId {
        FIRST_EXPENSE,
        FIRST_INCOME,
        AUTO_BACKUP,
        INCOME_PLAN,
    }

    data class Step(
        val id: StepId,
        val title: String,
        val subtitle: String,
        val done: Boolean,
    )

    data class Progress(
        val steps: List<Step>,
        val completedCount: Int,
        val totalCount: Int,
        val shouldShow: Boolean,
    )

    fun buildProgress(
        transactions: List<TransactionEntity>,
        autoBackupEnabled: Boolean,
        hasIncomePlan: Boolean,
        checklistDismissed: Boolean,
    ): Progress {
        val hasExpense = transactions.any { it.type == "expense" }
        val hasIncome = transactions.any { it.type == "income" }
        val steps = listOf(
            Step(
                id = StepId.FIRST_EXPENSE,
                title = "Записать первый расход",
                subtitle = "Например, покупки в «Продукты»",
                done = hasExpense,
            ),
            Step(
                id = StepId.FIRST_INCOME,
                title = "Записать первый доход",
                subtitle = "Зарплата, аванс или перевод",
                done = hasIncome,
            ),
            Step(
                id = StepId.AUTO_BACKUP,
                title = "Включить автобэкап",
                subtitle = "Чтобы не потерять данные",
                done = autoBackupEnabled,
            ),
            Step(
                id = StepId.INCOME_PLAN,
                title = "Настроить план дохода",
                subtitle = "Для календаря и «сколько можно тратить»",
                done = hasIncomePlan,
            ),
        )
        val completedCount = steps.count { it.done }
        val shouldShow = !checklistDismissed && completedCount < steps.size
        return Progress(
            steps = steps,
            completedCount = completedCount,
            totalCount = steps.size,
            shouldShow = shouldShow,
        )
    }
}
