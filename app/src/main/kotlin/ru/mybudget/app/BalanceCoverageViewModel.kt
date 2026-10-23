package ru.mybudget.app

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.mybudget.app.data.BudgetDatabase

class BalanceCoverageViewModel(private val saved:SavedStateHandle):ViewModel() {
    data class State(val working:Boolean=false,val done:Boolean=false,val error:String?=null)
    private val mutable=MutableStateFlow(State())
    val state=mutable.asStateFlow()
    private val request=saved.get<String>("coverage_request") ?: java.util.UUID.randomUUID().toString().also { saved["coverage_request"]=it }
    fun save(budget:Int,target:Int,amounts:Map<Int,Double>,expected:Map<Int,Double>) {
        if(mutable.value.working || mutable.value.done) return
        mutable.value=State(working=true)
        viewModelScope.launch {
            val app=BudgetApplication.instance
            try { withContext(Dispatchers.IO) { BalanceCoverage.execute(BudgetDatabase.getInstance(app),budget,target,amounts,expected,request) }
            } catch(e:kotlinx.coroutines.CancellationException) { throw e
            } catch(e:Exception) { mutable.value=State(error=e.message.orEmpty());return@launch }
            app.applicationScope.launch {
                try { BudgetManager.getInstance(app).getCategoriesAsync(forceReload=true);BudgetWidgetProvider.updateAll(app)
                } catch(e:kotlinx.coroutines.CancellationException) { throw e
                } catch(e:Exception) { android.util.Log.w("Coverage","Committed; refresh failed",e) }
            }
            mutable.value=State(done=true)
        }
    }
    fun clearError() { if(!mutable.value.working && !mutable.value.done) mutable.value=State() }
}
