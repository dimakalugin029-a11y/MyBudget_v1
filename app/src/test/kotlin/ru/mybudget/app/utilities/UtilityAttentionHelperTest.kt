package ru.mybudget.app.utilities

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class UtilityAttentionHelperTest {
    @Test
    fun isWithinAttentionWindow_startsFiveDaysBeforeReminderDay() {
        val dayBeforeWindow = LocalDate.of(2026, 8, 9)
        val firstDayInWindow = LocalDate.of(2026, 8, 10)
        assertFalse(UtilityAttentionHelper.isWithinAttentionWindow(reminderDay = 15, today = dayBeforeWindow))
        assertTrue(UtilityAttentionHelper.isWithinAttentionWindow(reminderDay = 15, today = firstDayInWindow))
    }

    @Test
    fun isWithinAttentionWindow_clampsReminderDay31InFebruary() {
        val dayBeforeWindow = LocalDate.of(2026, 2, 22)
        val firstDayInWindow = LocalDate.of(2026, 2, 23)
        assertFalse(UtilityAttentionHelper.isWithinAttentionWindow(reminderDay = 31, today = dayBeforeWindow))
        assertTrue(UtilityAttentionHelper.isWithinAttentionWindow(reminderDay = 31, today = firstDayInWindow))
    }
}
