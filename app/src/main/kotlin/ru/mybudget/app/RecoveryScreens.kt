package ru.mybudget.app

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

open class LayoutActivity(
    private val layoutRes: Int,
    private val titleRes: Int = 0,
) : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(layoutRes)
        val title = if (titleRes != 0) getString(titleRes) else ""
        ScreenHeaderHelper.setup(this, title)
    }
}
