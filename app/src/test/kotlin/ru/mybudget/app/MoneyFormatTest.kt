package ru.mybudget.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MoneyFormatTest {
    @Test
    fun roundMoney_roundsToTwoDecimals() {
        assertEquals(12.34, MoneyFormat.roundMoney(12.345), 0.0001)
        assertEquals(12.36, MoneyFormat.roundMoney(12.355), 0.0001)
    }

    @Test
    fun formatRub_usesRussianLocale() {
        val formatted = MoneyFormat.formatRub(1234.56)
        assertTrue(formatted.contains("234,56"))
        assertTrue(formatted.endsWith("\u20BD"))
    }

    @Test
    fun format_groupsThousandsWithNarrowSpace() {
        assertEquals("156\u202F632,00", MoneyFormat.format(156632.0))
        assertEquals("9\u202F999,99", MoneyFormat.format(9999.99))
        assertEquals("999,00", MoneyFormat.format(999.0))
        assertEquals("0,00", MoneyFormat.format(0.0))
    }

    @Test
    fun parse_acceptsCommaAndSpaces() {
        assertEquals(1234.56, MoneyFormat.parse("1 234,56")!!, 0.0001)
        assertEquals(1234.56, MoneyFormat.parse("1\u202F234,56")!!, 0.0001)
        assertEquals(1234.56, MoneyFormat.parse("1\u00A0234,56")!!, 0.0001)
        assertEquals(1234.56, MoneyFormat.parse("1\u2009234,56")!!, 0.0001)
        assertEquals(12.5, MoneyFormat.parse("12.5")!!, 0.0001)
    }

    @Test
    fun parse_blankReturnsNull() {
        assertNull(MoneyFormat.parse(null))
        assertNull(MoneyFormat.parse(""))
        assertNull(MoneyFormat.parse("   "))
    }

    @Test
    fun parseQuantity_preservesSixDecimals() {
        assertEquals(1.234567, MoneyFormat.parseQuantity("1,234567")!!, 0.0000001)
    }
}