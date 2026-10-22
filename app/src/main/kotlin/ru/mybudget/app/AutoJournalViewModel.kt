package ru.mybudget.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.mybudget.app.auto.AutoEntry
import ru.mybudget.app.auto.AutoEntryType
import ru.mybudget.app.data.AutoRepository
import ru.mybudget.app.data.BudgetDatabase

class AutoJournalViewModel:ViewModel() {
    sealed interface Command {
        data class VehicleArchive(val id:Int,val archived:Boolean):Command
        data class EntryArchive(val type:AutoEntryType,val id:Int,val archived:Boolean):Command
        data class Cancel(val expected:AutoEntry):Command
    }
    sealed interface State {
        data object Idle:State
        data object Working:State
        data object Done:State
        data class Failed(val message:String):State
    }
    private val mutable=MutableStateFlow<State>(State.Idle)
    val state=mutable.asStateFlow()
    fun execute(command:Command) {
        if(mutable.value != State.Idle) return
        mutable.value=State.Working
        viewModelScope.launch {
            val app=BudgetApplication.instance
            try {
                withContext(Dispatchers.IO) {
                    val repository=AutoRepository(BudgetDatabase.getInstance(app))
                    when(command) {
                        is Command.VehicleArchive -> repository.setVehicleArchived(command.id,command.archived)
                        is Command.EntryArchive -> repository.setEntryArchived(command.type,command.id,command.archived)
                        is Command.Cancel -> repository.cancelEntry(command.expected)
                    }
                }
            } catch(cancelled:kotlinx.coroutines.CancellationException) { throw cancelled
            } catch(e:Exception) { mutable.value=State.Failed(e.message.orEmpty());return@launch }
            app.applicationScope.launch {
                try {
                    BudgetManager.getInstance(app).getCategoriesAsync(forceReload=true)
                    BudgetWidgetProvider.updateAll(app)
                } catch(cancelled:kotlinx.coroutines.CancellationException) { throw cancelled
                } catch(e:Exception) { android.util.Log.w("AutoJournal","Command finished; refresh failed",e) }
            }
            mutable.value=State.Done
        }
    }
    fun acknowledge() { if(mutable.value != State.Working) mutable.value=State.Idle }
}
