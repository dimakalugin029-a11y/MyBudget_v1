package ru.mybudget.app

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatDelegate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import ru.mybudget.app.setup.AppLockPreferences

class BudgetApplication : Application(), Application.ActivityLifecycleCallbacks {

    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var foregroundActivities = 0
    private var wentToBackgroundAt = 0L

    override fun onCreate() {
        super.onCreate()
        instance = this
        registerActivityLifecycleCallbacks(this)
        applyThemeFromPreferences()
        BudgetManager.getInstance(this)
    }

    private fun applyThemeFromPreferences() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val mode = prefs.getString(KEY_THEME, VALUE_LIGHT) ?: VALUE_LIGHT
        val nightMode = when (mode) {
            VALUE_DARK -> AppCompatDelegate.MODE_NIGHT_YES
            VALUE_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        AppCompatDelegate.setDefaultNightMode(nightMode)
    }

    override fun onActivityStarted(activity: Activity) {
        foregroundActivities++
        if (foregroundActivities == 1 &&
            activity !is LockActivity &&
            AppLockPreferences.shouldLock(this, wentToBackgroundAt)
        ) {
            activity.startActivity(
                Intent(this, LockActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    override fun onActivityStopped(activity: Activity) {
        foregroundActivities--
        if (foregroundActivities == 0) {
            wentToBackgroundAt = System.currentTimeMillis()
        }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit

    companion object {
        const val PREFS_NAME = "mybudget_prefs"
        const val KEY_THEME = "theme_mode"
        const val VALUE_DARK = "dark"
        const val VALUE_LIGHT = "light"
        const val VALUE_SYSTEM = "system"

        lateinit var instance: BudgetApplication
            private set
    }
}
