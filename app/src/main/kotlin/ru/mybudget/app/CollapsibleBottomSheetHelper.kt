package ru.mybudget.app

import android.view.View
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetBehavior

object CollapsibleBottomSheetHelper {
    fun attach(sheet: View, header: View, chevron: TextView, peekHeightPx: Int) {
        val behavior = BottomSheetBehavior.from(sheet)
        behavior.isHideable = false
        behavior.peekHeight = peekHeightPx
        behavior.state = BottomSheetBehavior.STATE_COLLAPSED
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
