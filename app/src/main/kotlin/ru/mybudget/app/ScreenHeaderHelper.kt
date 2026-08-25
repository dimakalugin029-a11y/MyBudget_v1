package ru.mybudget.app

import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

object ScreenHeaderHelper {
    fun setup(activity: AppCompatActivity, title: CharSequence, emoji: String? = null) {
        activity.findViewById<ImageButton>(R.id.screenHeaderBack)?.setOnClickListener {
            activity.onBackPressedDispatcher.onBackPressed()
        }
        activity.findViewById<TextView>(R.id.screenHeaderTitle)?.text = title
        activity.findViewById<TextView>(R.id.screenHeaderEmoji)?.apply {
            if (emoji.isNullOrBlank()) {
                visibility = View.GONE
            } else {
                text = emoji
                visibility = View.VISIBLE
            }
        }
        hideToolbarActions(activity)
    }

    fun bindAction(
        activity: AppCompatActivity,
        iconRes: Int,
        contentDescriptionRes: Int,
        onClick: () -> Unit,
    ) {
        val button = activity.findViewById<ImageButton>(R.id.screenHeaderAction) ?: return
        button.setImageDrawable(ContextCompat.getDrawable(button.context, iconRes))
        button.contentDescription = button.context.getString(contentDescriptionRes)
        button.visibility = View.VISIBLE
        button.setOnClickListener { onClick() }
    }

    fun bindSecondaryAction(
        activity: AppCompatActivity,
        iconRes: Int,
        contentDescriptionRes: Int,
        onClick: () -> Unit,
    ) {
        val button = activity.findViewById<ImageButton>(R.id.screenHeaderActionSecondary) ?: return
        button.setImageDrawable(ContextCompat.getDrawable(button.context, iconRes))
        button.contentDescription = button.context.getString(contentDescriptionRes)
        button.visibility = View.VISIBLE
        button.setOnClickListener { onClick() }
    }

    fun hideToolbarActions(activity: AppCompatActivity) {
        activity.findViewById<View>(R.id.screenHeaderAction)?.visibility = View.GONE
        activity.findViewById<View>(R.id.screenHeaderActionSecondary)?.visibility = View.GONE
    }
}
