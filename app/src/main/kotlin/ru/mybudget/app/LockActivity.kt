package ru.mybudget.app

import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import ru.mybudget.app.security.PinAttemptController
import ru.mybudget.app.setup.AppLockPreferences

class LockActivity:AppCompatActivity() {
    private val model:PinUnlockViewModel by viewModels()
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        if (!AppLockPreferences.isEnabled(this)) { finishUnlock();return }
        setContentView(R.layout.activity_lock)
        val pin=findViewById<EditText>(R.id.lockPinInput)
        val button=findViewById<MaterialButton>(R.id.lockUnlockButton)
        val biometric=findViewById<MaterialButton>(R.id.lockBiometricButton)
        val status=findViewById<TextView>(R.id.lockStatusText)
        var remaining:Long?=null
        var unavailable=false
        var keyboardShown=false
        val biometricAvailable=AppLockPreferences.isBiometricEnabled(this) &&
            BiometricManager.from(this).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK)==BiometricManager.BIOMETRIC_SUCCESS
        biometric.visibility=if(biometricAvailable) View.VISIBLE else View.GONE
        val prompt=BiometricPrompt(this,ContextCompat.getMainExecutor(this),object:BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result:BiometricPrompt.AuthenticationResult) { model.biometricVerified(applicationContext) }
            override fun onAuthenticationError(errorCode:Int,errString:CharSequence) { model.biometricCancelled() }
        })
        fun render() {
            val state=model.state.value
            val enabled=!state.working && !state.verified && remaining==0L && !unavailable
            pin.isEnabled=enabled;button.isEnabled=enabled
            biometric.isEnabled=!state.working && !state.verified
            status.text=when {
                state.working -> getString(R.string.pin_checking)
                unavailable -> getString(R.string.pin_check_unavailable)
                (remaining ?: 0)>0 -> getString(R.string.pin_wait,((remaining!!+999)/1000).toInt())
                state.message!=null -> getString(state.message)
                else -> ""
            }
            if(enabled && !keyboardShown) {
                keyboardShown=true
                pin.requestFocus()
                pin.post { if(pin.isEnabled) getSystemService(InputMethodManager::class.java)?.showSoftInput(pin,InputMethodManager.SHOW_IMPLICIT) }
            }
        }
        button.setOnClickListener { if(button.isEnabled) model.verify(applicationContext,pin.text.toString()) }
        pin.setOnEditorActionListener { _,actionId,event ->
            val handled=actionId==EditorInfo.IME_ACTION_DONE || (actionId==EditorInfo.IME_NULL && event?.keyCode==KeyEvent.KEYCODE_ENTER)
            if(handled && button.isEnabled) model.verify(applicationContext,pin.text.toString())
            handled
        }
        biometric.setOnClickListener {
            if(model.beginBiometric()) prompt.authenticate(BiometricPrompt.PromptInfo.Builder()
                .setTitle(getString(R.string.lock_biometric_title)).setSubtitle(getString(R.string.lock_biometric_subtitle))
                .setNegativeButtonText(getString(android.R.string.cancel)).build())
        }
        onBackPressedDispatcher.addCallback(this,object:OnBackPressedCallback(true) { override fun handleOnBackPressed() { finishAffinity() } })
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    model.state.collect { state ->
                        if(state.verified) { ImeHelper.hideKeyboard(this@LockActivity,pin);finishUnlock() }
                        else { if(!state.working && state.message==R.string.lock_pin_wrong) pin.text.clear();render() }
                    }
                }
                launch {
                    while(true) {
                        if(!model.state.value.working) {
                            try { remaining=PinAttemptController.remaining(applicationContext);unavailable=false
                            } catch(cancelled:kotlinx.coroutines.CancellationException) { throw cancelled
                            } catch(_:Exception) { unavailable=true }
                        }
                        render();delay(500)
                    }
                }
            }
        }
        render()
    }
    private fun finishUnlock() { setResult(RESULT_OK);finish() }
}
