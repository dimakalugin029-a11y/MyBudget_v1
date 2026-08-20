package ru.mybudget.app

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.RadioGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.widget.SwitchCompat

class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        ScreenHeaderHelper.setup(
            this,
            getString(R.string.settings_title),
            getString(R.string.main_icon_settings),
        )

        val prefs = getSharedPreferences(BudgetApplication.PREFS_NAME, MODE_PRIVATE)
        val current = prefs.getString(BudgetApplication.KEY_THEME, BudgetApplication.VALUE_LIGHT)
        val checkedId = when (current) {
            BudgetApplication.VALUE_DARK -> R.id.themeDarkRadio
            BudgetApplication.VALUE_SYSTEM -> R.id.themeSystemRadio
            else -> R.id.themeLightRadio
        }
        findViewById<RadioGroup>(R.id.themeRadioGroup).check(checkedId)
        findViewById<RadioGroup>(R.id.themeRadioGroup).setOnCheckedChangeListener { _, id ->
            val mode = when (id) {
                R.id.themeDarkRadio -> BudgetApplication.VALUE_DARK
                R.id.themeSystemRadio -> BudgetApplication.VALUE_SYSTEM
                else -> BudgetApplication.VALUE_LIGHT
            }
            prefs.edit().putString(BudgetApplication.KEY_THEME, mode).apply()
            AppCompatDelegate.setDefaultNightMode(
                when (mode) {
                    BudgetApplication.VALUE_DARK -> AppCompatDelegate.MODE_NIGHT_YES
                    BudgetApplication.VALUE_SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                    else -> AppCompatDelegate.MODE_NIGHT_NO
                },
            )
        }

        findViewById<View>(R.id.defaultAmountsButton).setOnClickListener {
            startActivity(Intent(this, DefaultAmountsActivity::class.java))
        }
        findViewById<View>(R.id.rolloverButton).setOnClickListener {
            startActivity(Intent(this, RolloverActivity::class.java))
        }
        findViewById<View>(R.id.monthStartButton).setOnClickListener {
            startActivity(Intent(this, MonthStartActivity::class.java))
        }
        findViewById<View>(R.id.templatesButton).setOnClickListener {
            startActivity(Intent(this, BudgetActivity::class.java))
        }
        findViewById<View>(R.id.helpButton).setOnClickListener {
            startActivity(Intent(this, HelpActivity::class.java))
        }
        findViewById<View>(R.id.aboutButton).setOnClickListener {
            startActivity(Intent(this, AboutActivity::class.java))
        }
        findViewById<View>(R.id.exportButton).setOnClickListener {
            Toast.makeText(this, R.string.settings_backup_hint, Toast.LENGTH_LONG).show()
        }
        findViewById<View>(R.id.importButton).setOnClickListener {
            Toast.makeText(this, R.string.settings_backup_hint, Toast.LENGTH_LONG).show()
        }
        findViewById<SwitchCompat>(R.id.appLockSwitch).setOnCheckedChangeListener { button, checked ->
            if (checked) {
                button.isChecked = false
                Toast.makeText(this, R.string.lock_settings_hint, Toast.LENGTH_SHORT).show()
            }
        }
    }
}
