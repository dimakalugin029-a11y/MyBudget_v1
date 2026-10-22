package ru.mybudget.app

import android.view.View
import android.widget.TextView

object MenuRowHelper {
    fun bind(row: View, icon: String, title: String) {
        row.findViewById<TextView>(R.id.rowIcon)?.text = icon
        row.findViewById<android.widget.ImageView>(R.id.dashboardRowIcon)?.setImageResource(when (icon) {
            "🏠" -> R.drawable.ic_menu_home
            "📅" -> R.drawable.ic_nav_plan
            "📈" -> R.drawable.ic_nav_analysis
            "💲", "🏦" -> R.drawable.ic_nav_budget
            "📤", "📥" -> R.drawable.ic_menu_repeat
            else -> R.drawable.ic_menu_journal
        })
        row.findViewById<TextView>(R.id.rowTitle)?.text = title
        row.findViewById<TextView>(R.id.rowSubtitle)?.visibility = View.GONE
    }

    fun bind(row: View, icon: String, title: String, onClick: () -> Unit) {
        bind(row, icon, title)
        val target = row.findViewById<View>(R.id.mainListRowRoot) ?: row
        target.setOnClickListener { onClick() }
    }

    fun bindAttention(
        row: View,
        icon: String,
        title: String,
        subtitle: String? = null,
        onClick: () -> Unit,
    ) {
        row.findViewById<TextView>(R.id.rowIcon)?.text = icon
        row.findViewById<TextView>(R.id.rowTitle)?.text = title
        row.findViewById<TextView>(R.id.rowSubtitle)?.apply {
            if (subtitle.isNullOrBlank()) {
                visibility = View.GONE
            } else {
                visibility = View.VISIBLE
                text = subtitle
            }
        }
        val target = row.findViewById<View>(R.id.mainListRowRoot) ?: row
        target.setOnClickListener { onClick() }
    }
}
