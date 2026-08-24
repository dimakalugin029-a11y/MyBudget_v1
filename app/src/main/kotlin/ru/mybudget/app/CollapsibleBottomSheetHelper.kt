package ru.mybudget.app

import android.view.View
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior

object CollapsibleBottomSheetHelper {
    fun attach(sheet: View, header: View, chevron: TextView, peekHeightPx: Int) {
        val behavior = BottomSheetBehavior.from(sheet)
        behavior.isHideable = false
        behavior.skipCollapsed = false
        behavior.peekHeight = peekHeightPx
        behavior.state = BottomSheetBehavior.STATE_COLLAPSED
        bindToggle(sheet, header, chevron, behavior)
    }

    fun attach(
        sheet: View,
        header: View,
        chevron: TextView,
        scrollTarget: RecyclerView,
        extraBottomPaddingPx: Int = 0,
    ): BottomSheetBehavior<View> {
        val behavior = BottomSheetBehavior.from(sheet)
        behavior.isHideable = false
        behavior.skipCollapsed = false
        behavior.state = BottomSheetBehavior.STATE_COLLAPSED
        header.post {
            val peek = header.height.takeIf { it > 0 } ?: header.measuredHeight
            if (peek <= 0) return@post
            behavior.peekHeight = peek
            scrollTarget.setPadding(
                scrollTarget.paddingLeft,
                scrollTarget.paddingTop,
                scrollTarget.paddingRight,
                peek + extraBottomPaddingPx,
            )
        }
        bindToggle(sheet, header, chevron, behavior)
        return behavior
    }

    private fun bindToggle(
        sheet: View,
        header: View,
        chevron: TextView,
        behavior: BottomSheetBehavior<View>,
    ) {
        fun syncChevron() {
            chevron.setText(
                if (behavior.state == BottomSheetBehavior.STATE_EXPANDED) {
                    R.string.ui_chevron_down
                } else {
                    R.string.ui_chevron_up
                },
            )
        }
        syncChevron()
        header.setOnClickListener {
            behavior.state = if (behavior.state == BottomSheetBehavior.STATE_EXPANDED) {
                BottomSheetBehavior.STATE_COLLAPSED
            } else {
                BottomSheetBehavior.STATE_EXPANDED
            }
        }
        behavior.addBottomSheetCallback(object : BottomSheetBehavior.BottomSheetCallback() {
            override fun onStateChanged(bottomSheet: View, newState: Int) = syncChevron()
            override fun onSlide(bottomSheet: View, slideOffset: Float) = Unit
        })
    }
}
