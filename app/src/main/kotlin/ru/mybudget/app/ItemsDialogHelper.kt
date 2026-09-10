package ru.mybudget.app

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog

object ItemsDialogHelper {
    fun show(
        context: Context,
        title: CharSequence?,
        message: CharSequence,
        items: Array<out CharSequence>,
        negativeText: CharSequence? = null,
        onItemClick: (Int) -> Unit,
    ) {
        val messageView = TextView(context).apply {
            text = message
            textSize = 16f
        }
        val listView = ListView(context)
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                resources.getDimensionPixelSize(R.dimen.space_20),
                resources.getDimensionPixelSize(R.dimen.space_20),
                resources.getDimensionPixelSize(R.dimen.space_20),
                0,
            )
            addView(
                messageView,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    bottomMargin = resources.getDimensionPixelSize(R.dimen.space_12)
                },
            )
            addView(
                listView,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        val builder = AlertDialog.Builder(context)
            .setView(content)
            .apply {
                if (title != null) setTitle(title)
                if (negativeText != null) setNegativeButton(negativeText, null)
            }
        val dialog = builder.create()
        listView.adapter = ArrayAdapter(context, android.R.layout.simple_list_item_1, items)
        listView.setOnItemClickListener { _, _, position, _ ->
            onItemClick(position)
            dialog.dismiss()
        }
        capListHeight(listView)
        dialog.show()
    }

    private fun capListHeight(listView: ListView) {
        val maxHeight = (listView.resources.displayMetrics.heightPixels * 0.6f).toInt()
        listView.post {
            if (listView.width == 0) return@post
            val widthSpec = View.MeasureSpec.makeMeasureSpec(listView.width, View.MeasureSpec.AT_MOST)
            val heightSpec = View.MeasureSpec.makeMeasureSpec(maxHeight, View.MeasureSpec.AT_MOST)
            listView.measure(widthSpec, heightSpec)
            val params = listView.layoutParams
            params.height = listView.measuredHeight
            listView.layoutParams = params
        }
    }
}
