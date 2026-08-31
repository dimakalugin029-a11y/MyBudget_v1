package ru.mybudget.app

import ru.mybudget.app.data.BudgetRepository
import ru.mybudget.app.data.PaymentReminderEntity
import ru.mybudget.app.data.PlannedObligationEntity
import ru.mybudget.app.data.RecurringTransactionEntity
import java.text.SimpleDateFormat
import java.util.Locale

object ObligationLinkedSync {
    suspend fun sync(repository: BudgetRepository, obligation: PlannedObligationEntity) {
        if (obligation.id <= 0) return
        syncReminder(repository, obligation)
        syncRecurring(repository, obligation)
    }

    suspend fun onDeleted(repository: BudgetRepository, obligationId: Int) {
        if (obligationId <= 0) return
        repository.deleteReminderByObligationId(obligationId)
        repository.deleteRecurringByObligationId(obligationId)
    }

    private suspend fun syncReminder(repository: BudgetRepository, obligation: PlannedObligationEntity) {
        val existing = repository.getReminderByObligationId(obligation.id)
        val shouldHave = obligation.isActive &&
            obligation.remindEnabled &&
            obligation.categoryId > 0
        if (!shouldHave) {
            repository.deleteReminderByObligationId(obligation.id)
            return
        }
        val built = ObligationReminderHelper.buildReminderEntity(obligation) ?: return
        if (existing == null) {
            repository.insertReminderEntity(built)
        } else {
            repository.updateReminderEntity(
                built.copy(id = existing.id, createdAt = existing.createdAt),
            )
        }
    }

    private suspend fun syncRecurring(repository: BudgetRepository, obligation: PlannedObligationEntity) {
        val existing = repository.getRecurringByObligationId(obligation.id)
        val shouldHave = obligation.isActive &&
            obligation.autoPostEnabled &&
            obligation.categoryId > 0 &&
            obligation.periodType == PlannedObligationHelper.PERIOD_MONTHLY
        if (!shouldHave) {
            repository.deleteRecurringByObligationId(obligation.id)
            return
        }
        val built = ObligationReminderHelper.buildRecurringEntity(obligation) ?: return
        if (existing == null) {
            repository.insertRecurring(built)
        } else {
            repository.updateRecurring(
                built.copy(id = existing.id, createdAt = existing.createdAt),
            )
        }
    }
}
