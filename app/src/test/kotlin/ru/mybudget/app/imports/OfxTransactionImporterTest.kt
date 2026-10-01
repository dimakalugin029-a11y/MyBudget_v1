package ru.mybudget.app.imports

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfxTransactionImporterTest {
    @Test
    fun isOfx_detectsHeaderAndTransactions() {
        assertTrue(OfxTransactionImporter.isOfx("OFXHEADER:100\nDATA:OFXSGML"))
        assertTrue(OfxTransactionImporter.isOfx("<STMTTRN><TRNAMT>-100</TRNAMT></STMTTRN>"))
        assertFalse(OfxTransactionImporter.isOfx("date,amount\n2026-09-01,100"))
    }

    @Test
    fun parse_ofx1x_parsesTransactions() {
        val ofx = """
            OFXHEADER:100
            DATA:OFXSGML

            <STMTTRN>
            <TRNTYPE>DEBIT
            <DTPOSTED>20260901120000
            <TRNAMT>-1500.00
            <NAME>PYATEROCHKA
            <MEMO>Groceries
            </STMTTRN>
            <STMTTRN>
            <TRNTYPE>CREDIT
            <DTPOSTED>20260902
            <TRNAMT>45000,50
            <NAME>Salary
            </STMTTRN>
        """.trimIndent()

        val result = OfxTransactionImporter.parse(ofx)

        assertEquals(2, result.rows.size)
        assertEquals(0, result.skipped)
        assertEquals("2026-09-01", java.text.SimpleDateFormat("yyyy-MM-dd").format(java.util.Date(result.rows[0].dateMillis)))
        assertEquals("expense", result.rows[0].type)
        assertEquals(1500.0, result.rows[0].amount, 0.01)
        assertEquals("PYATEROCHKA · Groceries", result.rows[0].description)
        assertEquals("income", result.rows[1].type)
        assertEquals(45000.5, result.rows[1].amount, 0.01)
    }

    @Test
    fun parse_invalidTransactions_areSkipped() {
        val ofx = """
            <STMTTRN><DTPOSTED>20260901<TRNAMT>-100</STMTTRN>
            <STMTTRN><DTPOSTED>bad<TRNAMT>-100</STMTTRN>
        """.trimIndent()

        val result = OfxTransactionImporter.parse(ofx)

        assertEquals(1, result.rows.size)
        assertEquals(1, result.skipped)
    }
}
