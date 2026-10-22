package ru.mybudget.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.mybudget.app.data.BudgetDatabase
import ru.mybudget.app.setup.QuickExpensePreferences

class MoneySaveViewModel:ViewModel() {
    sealed interface State {
        data object Idle:State
        data class Saving(val command:SingleMoneyCommand):State
        data class Done(val result:SingleMoneyCommandExecutor.Result):State
        data class Failed(val message:String):State
    }
    private val mutable=MutableStateFlow<State>(State.Idle)
    val state=mutable.asStateFlow()
    fun save(command:SingleMoneyCommand) {
        if(mutable.value is State.Saving || mutable.value is State.Done) return
        mutable.value=State.Saving(command)
        viewModelScope.launch {
            val app=BudgetApplication.instance
            val result=try {
                withContext(Dispatchers.IO) { SingleMoneyCommandExecutor(BudgetDatabase.getInstance(app)).execute(command) }
            } catch(cancelled:kotlinx.coroutines.CancellationException) { throw cancelled
            } catch(e:Exception) { mutable.value=State.Failed(e.message.orEmpty());return@launch }
            app.applicationScope.launch {
                try {
                    if(result.outcome==SingleMoneyCommandExecutor.Outcome.SAVED && command.type=="expense") {
                        QuickExpensePreferences.saveLastExpense(app,command.categoryId,command.amount,command.description)
                        OverspendNotifier.checkAfterExpense(app,command.categoryId)
                    }
                    BudgetManager.getInstance(app).getCategoriesAsync(forceReload=true)
                    BudgetWidgetProvider.updateAll(app)
                } catch(cancelled:kotlinx.coroutines.CancellationException) { throw cancelled
                } catch(e:Exception) { android.util.Log.w("MoneySave","Command finished; refresh failed",e) }
            }
            mutable.value=State.Done(result)
        }
    }
    fun acknowledge() { if(mutable.value !is State.Saving) mutable.value=State.Idle }
}
