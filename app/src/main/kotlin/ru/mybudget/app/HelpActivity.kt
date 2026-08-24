package ru.mybudget.app

import android.os.Bundle
import android.view.LayoutInflater
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class HelpActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_help)
        ScreenHeaderHelper.setup(this, getString(R.string.help_title), "📖")
        val container = findViewById<LinearLayout>(R.id.helpSectionsContainer)
        val inflater = LayoutInflater.from(this)
        sections().forEach { section ->
            val view = inflater.inflate(R.layout.item_help_section, container, false)
            view.findViewById<TextView>(R.id.helpSectionTitle).setText(section.first)
            view.findViewById<TextView>(R.id.helpSectionBody).setText(section.second)
            container.addView(view)
        }
    }

    private fun sections(): List<Pair<Int, Int>> = listOf(
        R.string.help_s1_title to R.string.help_s1_body,
        R.string.help_s2_title to R.string.help_s2_body,
        R.string.help_s3_title to R.string.help_s3_body,
        R.string.help_s4_title to R.string.help_s4_body,
        R.string.help_s5_title to R.string.help_s5_body,
        R.string.help_s6_title to R.string.help_s6_body,
        R.string.help_s7_title to R.string.help_s7_body,
        R.string.help_s8_title to R.string.help_s8_body,
        R.string.help_s9_title to R.string.help_s9_body,
        R.string.help_s10_title to R.string.help_s10_body,
        R.string.help_s11_title to R.string.help_s11_body,
        R.string.help_s12_title to R.string.help_s12_body,
        R.string.help_s13_title to R.string.help_s13_body,
    )
}
