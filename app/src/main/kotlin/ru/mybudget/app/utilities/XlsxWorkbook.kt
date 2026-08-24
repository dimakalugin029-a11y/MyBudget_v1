package ru.mybudget.app.utilities

import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

sealed class CellValue {
    data class Text(val value: String) : CellValue()
    data class Number(val value: Double) : CellValue()
    data object Empty : CellValue()
}

class XlsxWorkbook {
    private val sheets = mutableListOf<Pair<String, List<List<CellValue>>>>()

    fun addSheet(name: String, rows: List<List<CellValue>>) {
        sheets += name.take(31) to rows
    }

    fun writeTo(output: OutputStream) {
        ZipOutputStream(output).use { zip ->
            fun put(path: String, content: String) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            put("[Content_Types].xml", contentTypes(sheets.size))
            put("_rels/.rels", ROOT_RELS)
            put("xl/workbook.xml", workbookXml(sheets.map { it.first }))
            put("xl/_rels/workbook.xml.rels", workbookRels(sheets.size))
            put("xl/styles.xml", STYLES)
            sheets.forEachIndexed { index, sheet ->
                put("xl/worksheets/sheet${index + 1}.xml", sheetXml(sheet.second))
            }
        }
    }

    private fun contentTypes(sheetCount: Int): String {
        val sheetOverrides = (1..sheetCount).joinToString("") { i ->
            """<Override PartName="/xl/worksheets/sheet$i.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>"""
        }
        return """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
<Default Extension="xml" ContentType="application/xml"/>
<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
<Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>
$sheetOverrides
</Types>"""
    }

    private fun workbookXml(sheetNames: List<String>): String {
        val tags = sheetNames.mapIndexed { i, name ->
            """<sheet name="${escapeXml(name)}" sheetId="${i + 1}" r:id="rId${i + 1}"/>"""
        }.joinToString("")
        return """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
<sheets>$tags</sheets>
</workbook>"""
    }

    private fun workbookRels(sheetCount: Int): String {
        val rels = (1..sheetCount).joinToString("") { i ->
            """<Relationship Id="rId$i" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet$i.xml"/>"""
        }
        val stylesId = sheetCount + 1
        return """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
$rels
<Relationship Id="rId$stylesId" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
</Relationships>"""
    }

    private fun sheetXml(rows: List<List<CellValue>>): String {
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        sb.append("""<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>""")
        rows.forEachIndexed { rowIndex, row ->
            val r = rowIndex + 1
            sb.append("""<row r="$r">""")
            row.forEachIndexed { colIndex, cell ->
                sb.append(cellToXml(colLetter(colIndex + 1) + r, cell))
            }
            sb.append("</row>")
        }
        sb.append("</sheetData></worksheet>")
        return sb.toString()
    }

    private fun cellToXml(ref: String, cell: CellValue): String = when (cell) {
        is CellValue.Text -> {
            val v = escapeXml(cell.value)
            """<c r="$ref" t="inlineStr"><is><t>$v</t></is></c>"""
        }
        is CellValue.Number -> {
            val v = if (cell.value % 1.0 == 0.0) cell.value.toLong().toString() else cell.value.toString()
            """<c r="$ref"><v>$v</v></c>"""
        }
        CellValue.Empty -> ""
    }

    private fun colLetter(col: Int): String {
        val sb = StringBuilder()
        var n = col
        while (n > 0) {
            val rem = (n - 1) % 26
            sb.insert(0, ('A'.code + rem).toChar())
            n = (n - 1) / 26
        }
        return sb.toString()
    }

    private fun escapeXml(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    companion object {
        private const val ROOT_RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
</Relationships>"""
        private const val STYLES = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
<fonts count="1"><font><sz val="11"/><name val="Calibri"/></font></fonts>
<fills count="1"><fill><patternFill patternType="none"/></fill></fills>
<borders count="1"><border/></borders>
<cellStyleXfs count="1"><xf/></cellStyleXfs>
<cellXfs count="1"><xf xfId="0"/></cellXfs>
</styleSheet>"""
    }
}
