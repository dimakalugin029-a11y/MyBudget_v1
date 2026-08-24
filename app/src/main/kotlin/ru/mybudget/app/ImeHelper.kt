package ru.mybudget.app

import android.app.Dialog
import android.content.Context
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.appcompat.app.AlertDialog

object ImeHelper {
    fun prepareDialogView(content: View): View {
        if (content is android.widget.ScrollView) return content
        val scroll = android.widget.ScrollView(content.context).apply {
            layoutParams = android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            addView(content)
        }
        return scroll
    }

    fun applyToDialog(dialog: Dialog, dialogContent: View, vararg editTexts: EditText) {
        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        if (editTexts.isEmpty()) return
        val scroll = dialogContent as? android.view.ViewGroup ?: return
        val extraOffset = (24 * scroll.resources.displayMetrics.density).toInt()
        for (editText in editTexts) {
            editText.setOnFocusChangeListener { v, hasFocus ->
                if (hasFocus) {
                    scroll.post {
                        val rect = android.graphics.Rect(0, 0, v.width, v.height + extraOffset)
                        scroll.requestChildRectangleOnScreen(v, rect, true)
                    }
                }
            }
        }
    }

    fun hideKeyboard(context: Context, view: View? = null) {
        val focused = view ?: (context as? android.app.Activity)?.currentFocus ?: return
        val token = focused.windowToken ?: return
        val imm = context.getSystemService(InputMethodManager::class.java) ?: return
        imm.hideSoftInputFromWindow(token, 0)
        focused.clearFocus()
    }
}

fun AlertDialog.Builder.showWithIme(
    content: View,
    editTexts: Array<out EditText>,
    onShow: ((AlertDialog) -> Unit)? = null,
): AlertDialog {
    val wrapped = ImeHelper.prepareDialogView(content)
    setView(wrapped)
    val dialog = create()
    dialog.setOnShowListener {
        ImeHelper.applyToDialog(dialog, wrapped, *editTexts)
        onShow?.invoke(dialog)
    }
    dialog.show()
    return dialog
}

fun AlertDialog.Builder.showWithIme(
    focus: EditText,
    editTexts: Array<out EditText> = arrayOf(focus),
    content: View? = null,
): AlertDialog {
    val view = content ?: ImeHelper.prepareDialogView(focus)
    if (content == null) {
        setView(view)
    }
    val dialog = create()
    dialog.setOnShowListener {
        focus.requestFocus()
        val imm = focus.context.getSystemService(InputMethodManager::class.java)
        imm?.showSoftInput(focus, InputMethodManager.SHOW_IMPLICIT)
        ImeHelper.applyToDialog(dialog, view, *editTexts)
    }
    dialog.show()
    return dialog
}
