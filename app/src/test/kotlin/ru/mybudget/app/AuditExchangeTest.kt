package ru.mybudget.app

import org.junit.Assert.*
import org.junit.Test

class AuditExchangeTest {
    @Test fun exportImportPreservesExpenseTypeAndMultilineDescription() {
        val note = "Масло, \"замена\"\nТО"
        val csv = "date,category,type,amount,description\n" + CsvCodec.row(listOf("2026-10-21", "Авто, ТО", "expense", "1200.50", note))
        val rows = ru.mybudget.app.imports.CsvTransactionImporter.parse(csv).rows
        assertEquals(1, rows.size)
        assertEquals("expense", rows.single().type)
        assertEquals(1200.5, rows.single().amount, 0.001)
        assertEquals(note, rows.single().description)
        assertEquals("Авто, ТО", rows.single().categoryName)
    }
    @Test fun csvEscapesCommaQuotesAndNewline() {
        assertEquals("2026-10-21,\"Авто, ремонт\",1200.50,\"Замена \"\"масла\"\"\nТО\"\n",
            CsvCodec.row(listOf("2026-10-21", "Авто, ремонт", "1200.50", "Замена \"масла\"\nТО")))
    }
    @Test fun archiveRejectsOversizedEntry() {
        assertTrue(runCatching { BackupArchiveHelper.readBounded(byteArrayOf(1, 2, 3).inputStream(), 2) }.isFailure)
        assertArrayEquals(byteArrayOf(1, 2, 3), BackupArchiveHelper.readBounded(byteArrayOf(1, 2, 3).inputStream(), 3))
    }
}
