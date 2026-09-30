package ru.mybudget.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MoneyFormatTest {

    @Test
    fun `format rounds to two decimals with grouping`() {
        assertEquals("1\u202F234,56", MoneyFormat.format(1234.56))
        assertEquals("0,10", MoneyFormat.format(0.1))
        assertEquals("123,46", MoneyFormat.format(123.456))
    }

    @Test
    fun `format handles negative values`() {
        assertEquals("-50,00", MoneyFormat.format(-50.0))
    }

    @Test
    fun `formatRub appends ruble sign`() {
        assertEquals("100,00 ₽", MoneyFormat.formatRub(100.0))
    }

    @Test
    fun `formatChartAxis uses compact units`() {
        assertEquals("1,2 млн", MoneyFormat.formatChartAxis(1_200_000.0))
        assertEquals("50 тыс", MoneyFormat.formatChartAxis(50_000.0))
        assertEquals("999,00", MoneyFormat.formatChartAxis(999.0))
    }

    @Test
    fun `formatQuantity shows up to six decimals without trailing zeros`() {
        assertEquals("1,5", MoneyFormat.formatQuantity(1.5))
        assertEquals("0,123457", MoneyFormat.formatQuantity(0.1234567))
    }

    @Test
    fun `roundMoney rounds half up at cents`() {
        assertEquals(12.34, MoneyFormat.roundMoney(12.345), 1e-9)
        assertEquals(12.34, MoneyFormat.roundMoney(12.344), 1e-9)
    }

    @Test
    fun `parse accepts comma decimal separator`() {
        assertEquals(12.34, MoneyFormat.parse("12,34")!!, 1e-9)
        assertEquals(12.34, MoneyFormat.parse("12.34")!!, 1e-9)
    }

    @Test
    fun `parse strips group separators`() {
        assertEquals(1234.56, MoneyFormat.parse("1\u202F234,56")!!, 1e-9)
        assertEquals(1234.56, MoneyFormat.parse("1\u00A0234,56")!!, 1e-9)
    }

    @Test
    fun `parse returns null for blank or invalid input`() {
        assertNull(MoneyFormat.parse(null))
        assertNull(MoneyFormat.parse(""))
        assertNull(MoneyFormat.parse("abc"))
    }

    @Test
    fun `parseQuantity keeps six decimals`() {
        assertEquals(0.123457, MoneyFormat.parseQuantity("0,1234567")!!, 1e-9)
    }
}
