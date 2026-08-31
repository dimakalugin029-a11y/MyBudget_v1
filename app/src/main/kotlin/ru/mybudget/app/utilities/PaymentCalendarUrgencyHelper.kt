package ru.mybudget.app.utilities

import android.graphics.Color

object PaymentCalendarUrgencyHelper {
    private const val RED = 0xFFF44336.toInt()
    private const val RED_SOFT = 0xFFFFCDD2.toInt()
    private const val GREEN = 0xFF2E7D32.toInt()
    private const val RED_OVERDUE = 0xFFB71C1C.toInt()

    /** Days until payment: 0 = today, negative = overdue. Income entries always use green. */
    fun accentColor(daysUntil: Int, isIncome: Boolean = false): Int {
        if (isIncome) return GREEN
        return when {
            daysUntil < 0 -> RED_OVERDUE
            daysUntil > 7 -> GREEN
            daysUntil == 0 -> RED
            else -> blend(RED_SOFT, RED, 1f - daysUntil / 7f)
        }
    }

    private fun blend(from: Int, to: Int, t: Float): Int {
        val ratio = t.coerceIn(0f, 1f)
        val r = (Color.red(from) + (Color.red(to) - Color.red(from)) * ratio).toInt()
        val g = (Color.green(from) + (Color.green(to) - Color.green(from)) * ratio).toInt()
        val b = (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * ratio).toInt()
        return Color.rgb(r, g, b)
    }
}
