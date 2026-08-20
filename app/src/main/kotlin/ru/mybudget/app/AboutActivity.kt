package ru.mybudget.app

import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton

class AboutActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_about)
        ScreenHeaderHelper.setup(this, getString(R.string.about_button), "ℹ️")
        findViewById<TextView>(R.id.aboutVersionText)?.text =
            getString(R.string.about_version, BuildConfig.VERSION_NAME)
        findViewById<TextView>(R.id.aboutFeaturesText)?.text = getString(R.string.about_features_body)
        findViewById<MaterialButton>(R.id.aboutOpenHelpButton)?.setOnClickListener {
            startActivity(Intent(this, HelpActivity::class.java))
        }
    }
}
