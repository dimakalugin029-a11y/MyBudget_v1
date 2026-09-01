package ru.mybudget.app.imports

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mybudget.app.setup.ImportCategoryMappingPreferences

class CsvTransactionImporterTest {
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
