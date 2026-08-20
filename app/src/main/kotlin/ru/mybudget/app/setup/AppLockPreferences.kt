package ru.mybudget.app.setup

import android.content.Context

object AppLockPreferences {
    fun shouldLock(context: Context, wentToBackgroundAt: Long): Boolean = false
}
