package ru.mybudget.app

import android.os.Bundle
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import ru.mybudget.app.setup.AppLockPreferences

class LockActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!AppLockPreferences.isEnabled(this)) {
            finishUnlock()
            return
        }
        setContentView(R.layout.activity_lock)

        val pinInput = findViewById<EditText>(R.id.lockPinInput)
        findViewById<MaterialButton>(R.id.lockUnlockButton).setOnClickListener {
            tryUnlock(pinInput)
        }
        pinInput.setOnEditorActionListener { _, actionId, event ->
            val handled = actionId == EditorInfo.IME_ACTION_DONE ||
                actionId == EditorInfo.IME_NULL && event?.keyCode == KeyEvent.KEYCODE_ENTER
            if (handled) tryUnlock(pinInput)
            handled
        }
        pinInput.post {
            pinInput.requestFocus()
            getSystemService(InputMethodManager::class.java)?.showSoftInput(pinInput, InputMethodManager.SHOW_IMPLICIT)
        }

        val biometricButton = findViewById<MaterialButton>(R.id.lockBiometricButton)
        if (AppLockPreferences.isBiometricEnabled(this) &&
            BiometricManager.from(this).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) ==
            BiometricManager.BIOMETRIC_SUCCESS
        ) {
            biometricButton.setOnClickListener { showBiometric() }
        } else {
            biometricButton.visibility = android.view.View.GONE
        }
    }

    private fun tryUnlock(pinInput: EditText) {
        val pin = pinInput.text.toString()
        if (pin.length < 4) {
            Toast.makeText(this, R.string.lock_pin_too_short, Toast.LENGTH_SHORT).show()
            return
        }
        if (AppLockPreferences.verifyPin(this, pin)) {
            ImeHelper.hideKeyboard(this, pinInput)
            finishUnlock()
        } else {
            Toast.makeText(this, R.string.lock_pin_wrong, Toast.LENGTH_SHORT).show()
            pinInput.text.clear()
            pinInput.requestFocus()
        }
    }

    private fun showBiometric() {
        val executor = ContextCompat.getMainExecutor(this)
        val prompt = BiometricPrompt(
            this,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    finishUnlock()
                }
            },
        )
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle(getString(R.string.lock_biometric_title))
                .setSubtitle(getString(R.string.lock_biometric_subtitle))
                .setNegativeButtonText(getString(android.R.string.cancel))
                .build(),
        )
    }

    private fun finishUnlock() {
        AppLockPreferences.markUnlocked(this)
        setResult(RESULT_OK)
        finish()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        finishAffinity()
    }
}
