package ru.mybudget.app

import android.view.View
import android.widget.TextView

object MenuRowHelper {
    fun bind(row: View, icon: String, title: String) {
        row.findViewById<TextView>(R.id.rowIcon)?.text = icon
        row.findViewById<TextView>(R.id.rowTitle)?.text = title
    }

    fun bind(row: View, icon: String, title: String, onClick: () -> Unit) {
        bind(row, icon, title)
        val target = row.findViewById<View>(R.id.mainListRowRoot) ?: row
        target.setOnClickListener { onClick() }
    }
}
