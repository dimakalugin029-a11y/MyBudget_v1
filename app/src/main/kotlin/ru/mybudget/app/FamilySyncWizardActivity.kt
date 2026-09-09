package ru.mybudget.app

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity

class FamilySyncWizardActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_family_sync_wizard)
        ScreenHeaderHelper.setup(this, getString(R.string.family_sync_title), "👨‍👩‍👧")
        findViewById<View>(R.id.familySyncParticipantsBtn).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<View>(R.id.familySyncWebDavBtn).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }
}
