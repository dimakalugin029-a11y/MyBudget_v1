package ru.mybudget.app.utilities

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class UtilityExcelParserTest {
    @Test
    fun normalizeMeterName_fixesLatinHomoglyphs() {
        assertEquals("ХВС", UtilityExcelParser.normalizeMeterName("XBC"))
        assertEquals("ГВС", UtilityExcelParser.normalizeMeterName("ГBC"))
    }

    @Test
    fun parsePeriodToEpochDay_parsesDottedDate() {
        val epoch = UtilityExcelParser.parsePeriodToEpochDay("15.03.2024")
        assertEquals(LocalDate.of(2024, 3, 15).toEpochDay(), epoch)
    }

    @Test
    fun parsePeriodToEpochDay_parsesRussianMonth() {
        val epoch = UtilityExcelParser.parsePeriodToEpochDay("1 января 2024")
        assertEquals(LocalDate.of(2024, 1, 1).toEpochDay(), epoch)
    }

    @Test
    fun parsePeriodToEpochDay_parsesExcelSerial() {
        val epoch = UtilityExcelParser.parsePeriodToEpochDay("45351")
        assertNotNull(epoch)
        assertEquals(LocalDate.ofEpochDay(epoch!!), LocalDate.of(1899, 12, 30).plusDays(45351))
    }

    @Test
    fun parsePeriodToEpochDay_blankReturnsNull() {
        assertNull(UtilityExcelParser.parsePeriodToEpochDay(""))
        assertNull(UtilityExcelParser.parsePeriodToEpochDay("   "))
    }

    @Test
    fun isExampleRow_detectsMarker() {
        assertTrue(MeterExcelFormat.isExampleRow("Кухня (пример)", "ХВС"))
        assertTrue(MeterExcelFormat.isExampleRow("Кухня", "ХВС (пример)"))
        assertFalse(MeterExcelFormat.isExampleRow("Кухня", "ХВС"))
    }
}
