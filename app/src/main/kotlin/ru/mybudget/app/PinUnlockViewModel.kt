package ru.mybudget.app

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import ru.mybudget.app.security.PinAttemptController

class PinUnlockViewModel:ViewModel() {
    data class State(val working:Boolean=false,val verified:Boolean=false,val message:Int?=null)
    private val mutable=MutableStateFlow(State())
    val state=mutable.asStateFlow()
    fun verify(context:Context,pin:String) {
        if (mutable.value.working || mutable.value.verified) return
        mutable.value=State(working=true)
        val app=context.applicationContext
        viewModelScope.launch {
            val result=PinAttemptController.verify(app,pin)
            mutable.value=when(result) {
                PinAttemptController.Result.Verified -> State(verified=true)
                PinAttemptController.Result.Wrong -> State(message=R.string.lock_pin_wrong)
                PinAttemptController.Result.TooShort -> State(message=R.string.lock_pin_too_short)
                PinAttemptController.Result.Locked -> State()
                PinAttemptController.Result.Unavailable -> State(message=R.string.pin_check_unavailable)
            }
        }
    }
    fun beginBiometric():Boolean {
        if (mutable.value.working || mutable.value.verified) return false
        mutable.value=State(working=true)
        return true
    }
    fun biometricCancelled() { if (!mutable.value.verified) mutable.value=State() }
    fun biometricVerified(context:Context) {
        val app=context.applicationContext
        viewModelScope.launch {
            try { PinAttemptController.biometricVerified(app);mutable.value=State(verified=true)
            } catch (cancelled:kotlinx.coroutines.CancellationException) { throw cancelled
            } catch (_:Exception) { mutable.value=State(message=R.string.pin_check_unavailable) }
        }
    }
}
