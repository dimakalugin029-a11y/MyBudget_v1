package ru.mybudget.app.utilities

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UtilityUserTemplateTest {
    @Test
    fun computedAmount_multipliesQuantityAndTariff() {
        assertEquals(123.45, UtilityUserTemplate.computedAmount(10.0, 12.345)!!, 0.001)
    }

    @Test
    fun computedAmount_nullWhenMissingInput() {
        assertNull(UtilityUserTemplate.computedAmount(null, 1.0))
        assertNull(UtilityUserTemplate.computedAmount(1.0, null))
    }

    @Test
    fun formatPeriod_russianMonthYear() {
        assertEquals("март 2024", UtilityUserTemplate.formatPeriod(2024, 3))
    }
}
