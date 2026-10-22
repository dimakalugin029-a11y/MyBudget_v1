package ru.mybudget.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.mybudget.app.auto.AutoEntry
import ru.mybudget.app.auto.AutoPaymentMethod
import ru.mybudget.app.auto.AutoPreferences
import ru.mybudget.app.data.AutoRepository

/** The write survives Activity recreation; UI refresh failures cannot turn a committed save into a failed save. */
class AutoEntrySaveViewModel : ViewModel() {
    sealed interface State {
        data object Idle : State
        data object Saving : State
        data class Saved(val id: Int) : State
        data class Failed(val message: String) : State
    }
    private val mutableState = MutableStateFlow<State>(State.Idle)
    val state = mutableState.asStateFlow()
    fun save(repository: AutoRepository, entry: AutoEntry, requestId: String) {
        if (mutableState.value == State.Saving || mutableState.value is State.Saved) return
        mutableState.value = State.Saving
        viewModelScope.launch {
            val id = try {
                withContext(Dispatchers.IO) { repository.saveEntry(entry, requestId) }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled
            } catch (e: Exception) {
                mutableState.value = State.Failed(e.message.orEmpty())
                return@launch
            }
            // Nonessential follow-up runs independently of this Activity and of save success.
            val app = BudgetApplication.instance
            app.applicationScope.launch {
                try {
                    if (entry.paymentMethod == AutoPaymentMethod.BUDGET) AutoPreferences.setDefaultCategoryId(app,
                        entry.type.name.lowercase(java.util.Locale.ROOT), entry.categoryId.toInt())
                    BudgetManager.getInstance(app).reloadCategoriesFromDatabase()
                    BudgetWidgetProvider.updateAll(app)
                    if (entry.paymentMethod == AutoPaymentMethod.BUDGET) OverspendNotifier.checkAfterExpense(app, entry.categoryId.toInt())
                } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled
                } catch (e: Exception) { android.util.Log.w("AutoEntrySave", "Saved; refresh failed", e) }
            }
            mutableState.value = State.Saved(id)
        }
    }
}
