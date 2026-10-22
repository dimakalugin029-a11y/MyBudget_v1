package ru.mybudget.app

import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged

/** Keyboard and validation behaviour shared by single-operation forms. */
object OperationFormUi {
    fun bindAmount(activity: AppCompatActivity, amount: EditText, chooseCategory: () -> Unit) {
        amount.doAfterTextChanged { amount.error = null }
        amount.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_NEXT || actionId == EditorInfo.IME_ACTION_DONE) {
                ImeHelper.hideKeyboard(activity, amount)
                chooseCategory()
                true
            } else false
        }
    }

    fun amount(input: EditText, emptyMessage: Int): Double? {
        val value = MoneyFormat.parse(input.text)
        val error = when {
            input.text.isNullOrBlank() -> emptyMessage
            value == null || !value.isFinite() || value <= 0.0 -> R.string.error_invalid_amount
            else -> null
        }
        if (error != null) {
            input.error = input.context.getString(error)
            input.requestFocus()
            return null
        }
        return value
    }
}
