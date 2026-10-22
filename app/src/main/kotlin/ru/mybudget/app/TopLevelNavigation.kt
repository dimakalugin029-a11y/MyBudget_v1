package ru.mybudget.app

import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.bottomnavigation.BottomNavigationView

/** The three local sections share MainActivity; Budget and Analysis keep their existing screens. */
object TopLevelNavigation {
    const val EXTRA_DESTINATION = "main_destination"

    enum class Destination(val menuId: Int, val titleId: Int) {
        OVERVIEW(R.id.nav_overview, R.string.nav_overview),
        BUDGET(R.id.nav_budget, R.string.nav_budget),
        PLAN(R.id.nav_plan, R.string.nav_plan),
        ANALYSIS(R.id.nav_analysis, R.string.nav_analysis),
        MORE(R.id.nav_more, R.string.nav_more);

        companion object {
            fun fromName(name: String?): Destination = entries.firstOrNull { it.name == name } ?: OVERVIEW
        }
    }

    fun bind(
        activity: AppCompatActivity,
        selected: Destination,
        onSelected: (Destination) -> Unit = { open(activity, it) },
        onReselected: () -> Unit = {},
    ) {
        val navigation = activity.findViewById<BottomNavigationView>(R.id.mainNavigation)
        navigation.isItemActiveIndicatorEnabled = false
        navigation.selectedItemId = selected.menuId
        navigation.setOnItemSelectedListener { item ->
            val destination = Destination.entries.firstOrNull { it.menuId == item.itemId }
            if (destination != null) onSelected(destination)
            // The destination screen owns the checked state. Do not briefly highlight a tab
            // in an activity that is about to leave the foreground.
            false
        }
        navigation.setOnItemReselectedListener { onReselected() }
    }

    fun select(activity: AppCompatActivity, destination: Destination) {
        activity.findViewById<BottomNavigationView>(R.id.mainNavigation)
            .menu.findItem(destination.menuId).isChecked = true
    }

    fun open(activity: AppCompatActivity, destination: Destination) {
        // Return to the existing root, discarding the previous tab's activity and its details.
        // MainActivity then opens the requested screen. Repeated tab changes cannot grow a stack.
        activity.startActivity(Intent(activity, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(EXTRA_DESTINATION, destination.name))
    }
}
