package ru.mybudget.app

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.mybudget.app.setup.AppLockPreferences

class PinSettingsViewModel:ViewModel() {
    enum class State { IDLE,SAVING,SAVED,FAILED }
    private val mutable=MutableStateFlow(State.IDLE)
    val state=mutable.asStateFlow()
    fun save(context:Context,pin:String) {
        if(mutable.value==State.SAVING) return
        mutable.value=State.SAVING
        val app=context.applicationContext
        viewModelScope.launch {
            try {
                require(pin.length in 4..12 && pin.all { it in '0'..'9' })
                withContext(Dispatchers.Default) {
                    AppLockPreferences.setPin(app,pin)
                    AppLockPreferences.setEnabled(app,true)
                    check(AppLockPreferences.markUnlocked(app,durable=true))
                }
                mutable.value=State.SAVED
            } catch(cancelled:kotlinx.coroutines.CancellationException) { throw cancelled
            } catch(_:Exception) { mutable.value=State.FAILED }
        }
    }
    fun acknowledge() { if(mutable.value!=State.SAVING) mutable.value=State.IDLE }
}
