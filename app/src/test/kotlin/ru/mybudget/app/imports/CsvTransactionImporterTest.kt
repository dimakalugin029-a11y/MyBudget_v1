package ru.mybudget.app.imports

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mybudget.app.setup.ImportCategoryMappingPreferences
import java.text.SimpleDateFormat
import java.util.Locale

class CsvTransactionImporterTest {
    @Test
    fun duplicateKey_matchesSameDayAmountAndNormalizedDescription() {
        val df = SimpleDateFormat("yyyyMMdd", Locale.US)
        val day = df.parse("20260901")!!.time
        val later = day + 5 * 60 * 60 * 1000L

        val base = CsvTransactionImporter.duplicateKey(day, "expense", 1500.0, "PYATEROCHKA 1234")
        val sameDayLater = CsvTransactionImporter.duplicateKey(later, "expense", 1500.001, "  pyaterochka   1234 ")
        val otherDay = CsvTransactionImporter.duplicateKey(day + 24 * 60 * 60 * 1000L, "expense", 1500.0, "PYATEROCHKA 1234")
        val otherType = CsvTransactionImporter.duplicateKey(day, "income", 1500.0, "PYATEROCHKA 1234")

        assertEquals(base, sameDayLater)
        assertNotEquals(base, otherDay)
        assertNotEquals(base, otherType)
    }

    @Test
    fun parse_bankHeaderFormat_recognizesTinkoffLikeCsv() {
        val csv = """
            Дата операции;Сумма операции;Описание
            01.09.2026;-1500.00;PYATEROCHKA 1234
            02.09.2026;45000.00;Зарплата перевод
        """.trimIndent()

        val result = CsvTransactionImporter.parse(csv)

        assertEquals(2, result.rows.size)
        assertEquals("expense", result.rows[0].type)
        assertEquals(1500.0, result.rows[0].amount, 0.01)
        assertEquals("PYATEROCHKA 1234", result.rows[0].description)
        assertEquals("income", result.rows[1].type)
    }

    @Test
    fun parse_sberSplitColumns_recognizesExpenseAndIncome() {
        val csv = """
            Дата;Описание операции;Расход;Приход
            01.09.2026;Магазин;1200.00;
            02.09.2026;Зарплата;;45000.00
        """.trimIndent()

        val result = CsvTransactionImporter.parse(csv)

        assertEquals(2, result.rows.size)
        assertEquals("expense", result.rows[0].type)
        assertEquals(1200.0, result.rows[0].amount, 0.01)
        assertEquals("income", result.rows[1].type)
        assertEquals(45000.0, result.rows[1].amount, 0.01)
    }

    @Test
    fun resolveCategoryId_usesSavedDescriptionRules() {
        val rules = listOf(
            ImportCategoryMappingPreferences.Rule("pyaterochka 1234", 7),
        )

        val categoryId = CsvTransactionImporter.resolveCategoryId(
            categoryName = "",
            labels = emptyMap(),
            description = "PYATEROCHKA 1234 Moscow",
            rules = rules,
        )

        assertEquals(7, categoryId)
    }
}
