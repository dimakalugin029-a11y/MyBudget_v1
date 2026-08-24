package ru.mybudget.app.utilities

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.zip.ZipInputStream

object UtilityExcelParser {
    private val ruMonthPrefixes = listOf(
        "январ", "феврал", "март", "апрел", "май", "мая", "июн",
        "июл", "август", "сентябр", "октябр", "ноябр", "декабр",
    )
    private val dotted = DateTimeFormatter.ofPattern("d.M.yyyy")
    private val displayFmt = DateTimeFormatter.ofPattern("dd.MM.yyyy")
    private val dottedRegex = Regex("""\d{1,2}\.\d{1,2}\.\d{4}""")
    private val russianPeriod = Regex("""(\d{1,2})\s+([а-яё]+)(?:\s+(\d{2,4}))?""")

    data class ParsedWorkbook(
        val bills: List<ParsedMonthBill>,
        val meterReadings: List<ParsedMeterReading>,
        val meterVerifications: List<ParsedMeterVerification> = emptyList(),
        val meterCatalog: List<ParsedMeterCatalogEntry> = emptyList(),
    )

    data class ParsedMonthBill(
        val year: Int,
        val month: Int,
        val apartmentArea: Double,
        val sections: List<ParsedSection>,
    )

    data class ParsedSection(
        val name: String,
        val lines: List<ParsedLine>,
    )

    data class ParsedLine(
        val groupLabel: String,
        val name: String,
        val quantity: Double?,
        val tariff: Double?,
        val amount: Double,
    )

    data class ParsedMeterReading(
        val groupName: String,
        val meterName: String,
        val periodLabel: String,
        val readingValue: Double,
        val consumption: Double?,
    )

    data class ParsedMeterVerification(
        val meterName: String,
        val dateLabel: String,
        val epochDay: Long?,
    )

    data class ParsedMeterCatalogEntry(
        val groupName: String,
        val meterName: String,
        val verificationLabel: String,
        val verificationEpochDay: Long?,
    )

    data class ParsedMetersOnly(
        val catalog: List<ParsedMeterCatalogEntry>,
        val readings: List<ParsedMeterReading>,
        val verifications: List<ParsedMeterVerification>,
    )

    private data class SheetGrid(val rows: List<List<String>>) {
        fun cell(row: Int, col: Int): String =
            rows.getOrNull(row)?.getOrNull(col)?.trim().orEmpty()

        val rowCount: Int get() = rows.size
    }

    fun normalizeMeterName(name: String): String {
        val s = name.trim()
        if (s.isEmpty()) return s
        return s.replace("XBC", "ХВС", ignoreCase = true)
            .replace("HBC", "ХВС", ignoreCase = true)
            .replace("ГBC", "ГВС", ignoreCase = true)
    }

    fun formatPeriodLabelForDisplay(periodLabel: String): String {
        val t = periodLabel.trim()
        if (t.isEmpty()) return t
        val serial = t.toDoubleOrNull()
        if (serial != null && serial > 30000.0 && serial < 100000.0) {
            return runCatching {
                LocalDate.of(1899, 12, 30).plusDays(serial.toLong()).format(displayFmt)
            }.getOrDefault(t)
        }
        return MeterDateParser.formatPeriodLabelForDisplay(t)
    }

    fun readingSortKey(periodLabel: String, sortOrder: Int): Long =
        parsePeriodToEpochDay(periodLabel) ?: sortOrder.toLong()

    fun parsePeriodToEpochDay(label: String): Long? {
        val t = label.trim()
        if (t.isEmpty()) return null
        val serial = t.toDoubleOrNull()
        if (serial != null && serial > 30000.0 && serial < 100000.0) {
            return runCatching {
                LocalDate.of(1899, 12, 30).plusDays(serial.toLong()).toEpochDay()
            }.getOrNull()
        }
        if (dottedRegex.matches(t)) {
            return runCatching { LocalDate.parse(t, dotted).toEpochDay() }.getOrNull()
        }
        return MeterDateParser.parseToDate(t)?.toEpochDay() ?: parseRussianPeriodToEpochDay(t)
    }

    fun parseVerificationDateInput(raw: String): Pair<String, Long>? {
        val epoch = parsePeriodToEpochDay(raw) ?: return null
        return LocalDate.ofEpochDay(epoch).format(displayFmt) to epoch
    }

    fun parse(input: InputStream): ParsedWorkbook {
        val entries = readZipEntries(input)
        val shared = parseSharedStrings(entries["xl/sharedStrings.xml"])
        val sheet1 = parseSheetGrid(entries["xl/worksheets/sheet1.xml"], shared)
        val sheet2 = parseSheetGrid(entries["xl/worksheets/sheet2.xml"], shared)
        val metersFromSecond = parseMetersSheet(sheet2)
        val metersFromFirst = if (sheet2.rowCount == 0) parseMetersSheet(sheet1) else ParsedMetersOnly(emptyList(), emptyList(), emptyList())
        val bills = parseCommunalSheet(sheet1).ifEmpty { parseCommunalSheet(sheet2) }
        return ParsedWorkbook(
            bills = bills,
            meterReadings = metersFromSecond.readings.ifEmpty { metersFromFirst.readings },
            meterVerifications = metersFromSecond.verifications.ifEmpty { metersFromFirst.verifications },
            meterCatalog = metersFromSecond.catalog.ifEmpty { metersFromFirst.catalog },
        )
    }

    fun parseMetersOnly(input: InputStream): ParsedMetersOnly {
        val entries = readZipEntries(input)
        val shared = parseSharedStrings(entries["xl/sharedStrings.xml"])
        val sheet1 = parseSheetGrid(entries["xl/worksheets/sheet1.xml"], shared)
        val sheet2 = parseSheetGrid(entries["xl/worksheets/sheet2.xml"], shared)
        val from1 = parseMetersSheet(sheet1)
        val from2 = parseMetersSheet(sheet2)
        return ParsedMetersOnly(
            catalog = from1.catalog + from2.catalog,
            readings = from1.readings + from2.readings,
            verifications = from1.verifications + from2.verifications,
        )
    }

    private fun parseRussianPeriodToEpochDay(text: String): Long? {
        val lower = text.lowercase(Locale("ru"))
        val match = russianPeriod.find(lower) ?: return null
        val day = match.groupValues[1].toIntOrNull() ?: return null
        val monthWord = match.groupValues[2]
        val month = ruMonthPrefixes.indexOfFirst { monthWord.startsWith(it) } + 1
        if (month <= 0) return null
        val yearRaw = match.groupValues.getOrNull(3)?.trim().orEmpty()
        val year = when (yearRaw.length) {
            4 -> yearRaw.toIntOrNull()
            2 -> yearRaw.toIntOrNull()?.let { if (it >= 50) it + 1900 else it + 2000 }
            else -> null
        } ?: return null
        return runCatching { LocalDate.of(year, month, day).toEpochDay() }.getOrNull()
    }

    private fun readZipEntries(input: InputStream): Map<String, ByteArray> {
        val out = linkedMapOf<String, ByteArray>()
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory) {
                    out[entry.name] = zip.readBytes()
                }
            }
        }
        return out
    }

    private fun parseSharedStrings(bytes: ByteArray?): List<String> {
        if (bytes == null) return emptyList()
        val result = mutableListOf<String>()
        val parser = newParser(bytes)
        var inSi = false
        val buf = StringBuilder()
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    if (parser.name.equals("si", true)) {
                        inSi = true
                        buf.setLength(0)
                    }
                }
                XmlPullParser.TEXT -> if (inSi) buf.append(parser.text.orEmpty())
                XmlPullParser.END_TAG -> {
                    if (parser.name.equals("si", true) && inSi) {
                        result += buf.toString()
                        inSi = false
                    }
                }
            }
            parser.next()
        }
        return result
    }

    private fun parseSheetGrid(bytes: ByteArray?, shared: List<String>): SheetGrid {
        if (bytes == null) return SheetGrid(emptyList())
        val cells = linkedMapOf<Pair<Int, Int>, String>()
        var maxRow = 0
        var maxCol = 0
        val parser = newParser(bytes)
        var cellRef = ""
        var cellType = ""
        var inV = false
        var inT = false
        val buf = StringBuilder()
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    val name = parser.name.orEmpty()
                    if (name.equals("c", true)) {
                        cellRef = attr(parser, "r")
                        cellType = attr(parser, "t")
                        buf.setLength(0)
                    } else if (name.equals("v", true)) {
                        inV = true
                        buf.setLength(0)
                    } else if (name.equals("t", true)) {
                        inT = true
                        buf.setLength(0)
                    }
                }
                XmlPullParser.TEXT -> if (inV || inT) buf.append(parser.text.orEmpty())
                XmlPullParser.END_TAG -> {
                    val name = parser.name.orEmpty()
                    if ((name.equals("v", true) && inV) || (name.equals("t", true) && inT)) {
                        val raw = buf.toString()
                        val value = when {
                            cellType.equals("s", true) -> shared.getOrNull(raw.toIntOrNull() ?: -1).orEmpty()
                            else -> raw
                        }
                        val (row, col) = parseCellRef(cellRef) ?: (0 to 0)
                        if (value.isNotBlank()) {
                            val previous = cells[row to col].orEmpty()
                            cells[row to col] = previous + value
                            maxRow = maxOf(maxRow, row)
                            maxCol = maxOf(maxCol, col)
                        }
                        inV = false
                        inT = false
                        buf.setLength(0)
                    }
                    if (name.equals("c", true)) {
                        inV = false
                        inT = false
                        buf.setLength(0)
                    }
                }
            }
            parser.next()
        }
        val rows = List(maxRow + 1) { r ->
            List(maxCol + 1) { c -> cells[r to c].orEmpty() }
        }
        return SheetGrid(rows)
    }

    private fun parseCommunalSheet(grid: SheetGrid): List<ParsedMonthBill> {
        val header = findHeaderRow(
            grid,
            listOf("год", "месяц"),
        ) ?: return emptyList()
        val cols = mapColumns(grid, header)
        val yearCol = cols["год"] ?: return emptyList()
        val monthCol = cols["месяц"] ?: return emptyList()
        val areaCol = cols.entries.firstOrNull { it.key.startsWith("площад") }?.value
        val sectionCol = cols.entries.firstOrNull { it.key.startsWith("раздел") }?.value
        val groupCol = cols[MeterExcelFormat.COL_GROUP.lowercase()]
            ?: cols.entries.firstOrNull { it.key.startsWith("групп") }?.value
        val nameCol = cols.entries.firstOrNull { it.key.startsWith("услуг") || it.key == "строка" }?.value
        val qtyCol = cols.entries.firstOrNull { it.key.startsWith("кол") }?.value
        val tarCol = cols.entries.firstOrNull { it.key.startsWith("тариф") }?.value
        val amountCol = cols.entries.firstOrNull { it.key.startsWith("сумм") }?.value
        data class AccLine(
            val section: String,
            val group: String,
            val name: String,
            val qty: Double?,
            val tariff: Double?,
            val amount: Double,
            val area: Double,
        )
        val byPeriod = linkedMapOf<Pair<Int, Int>, MutableList<AccLine>>()
        for (r in header + 1 until grid.rowCount) {
            val year = grid.cell(r, yearCol).toDoubleOrNull()?.toInt() ?: continue
            val month = grid.cell(r, monthCol).toDoubleOrNull()?.toInt() ?: continue
            if (month !in 1..12) continue
            val amount = amountCol?.let { parseNumber(grid.cell(r, it)) } ?: 0.0
            val name = nameCol?.let { grid.cell(r, it) }.orEmpty()
            val section = sectionCol?.let { grid.cell(r, it) }.orEmpty().ifBlank { "Платежи" }
            if (name.isBlank() && amount == 0.0) continue
            val line = AccLine(
                section = section,
                group = groupCol?.let { grid.cell(r, it) }.orEmpty(),
                name = name.ifBlank { section },
                qty = qtyCol?.let { parseNumber(grid.cell(r, it)) },
                tariff = tarCol?.let { parseNumber(grid.cell(r, it)) },
                amount = amount,
                area = areaCol?.let { parseNumber(grid.cell(r, it)) } ?: 0.0,
            )
            byPeriod.getOrPut(year to month) { mutableListOf() } += line
        }
        return byPeriod.map { (period, lines) ->
            val sections = lines.groupBy { it.section }.map { (sectionName, sectionLines) ->
                ParsedSection(
                    name = sectionName,
                    lines = sectionLines.map {
                        ParsedLine(it.group, it.name, it.qty, it.tariff, it.amount)
                    },
                )
            }
            ParsedMonthBill(period.first, period.second, lines.firstOrNull()?.area ?: 0.0, sections)
        }
    }

    private fun parseMetersSheet(grid: SheetGrid): ParsedMetersOnly {
        val catalog = mutableListOf<ParsedMeterCatalogEntry>()
        val readings = mutableListOf<ParsedMeterReading>()
        val catalogHeader = findHeaderRow(grid, listOf("счётчик", "счетчик")) { row, _ ->
            val joined = grid.rows.getOrNull(row).orEmpty().joinToString(" ").lowercase()
            joined.contains("поверк") && !joined.contains("показан") && !joined.contains("дата")
        }
        val readingsHeader = findHeaderRow(grid, listOf("показание")) { row, _ ->
            val joined = grid.rows.getOrNull(row).orEmpty().joinToString(" ").lowercase()
            joined.contains("дата") || joined.contains("показан")
        }
        if (catalogHeader != null) {
            val cols = mapColumns(grid, catalogHeader)
            val groupCol = cols.entries.firstOrNull { it.key.startsWith("групп") }?.value
            val meterCol = cols.entries.firstOrNull { it.key.contains("счётчик") || it.key.contains("счетчик") }?.value
            val verCol = cols.entries.firstOrNull { it.key.contains("поверк") }?.value
            if (meterCol != null) {
                val stopAt = readingsHeader?.takeIf { it > catalogHeader } ?: grid.rowCount
                for (r in catalogHeader + 1 until stopAt) {
                    val meter = normalizeMeterName(grid.cell(r, meterCol))
                    val group = groupCol?.let { grid.cell(r, it) }.orEmpty()
                    if (meter.isBlank() || MeterExcelFormat.isExampleRow(group, meter)) continue
                    if (looksLikeSectionTitle(grid.cell(r, meterCol))) continue
                    val verRaw = verCol?.let { grid.cell(r, it) }.orEmpty()
                    val parsedVer = parseVerificationDateInput(verRaw)
                    catalog += ParsedMeterCatalogEntry(
                        groupName = group,
                        meterName = meter,
                        verificationLabel = parsedVer?.first ?: verRaw,
                        verificationEpochDay = parsedVer?.second,
                    )
                }
            }
        }
        if (readingsHeader != null) {
            val cols = mapColumns(grid, readingsHeader)
            val groupCol = cols.entries.firstOrNull { it.key.startsWith("групп") }?.value
            val meterCol = cols.entries.firstOrNull { it.key.contains("счётчик") || it.key.contains("счетчик") }?.value
            val dateCol = cols.entries.firstOrNull { it.key.contains("дата") || it.key.contains("период") }?.value
            val readingCol = cols.entries.firstOrNull { it.key.contains("показан") }?.value
            val consCol = cols.entries.firstOrNull { it.key.contains("расход") }?.value
            if (meterCol != null && readingCol != null) {
                for (r in readingsHeader + 1 until grid.rowCount) {
                    val meter = normalizeMeterName(grid.cell(r, meterCol))
                    val group = groupCol?.let { grid.cell(r, it) }.orEmpty()
                    if (meter.isBlank() || MeterExcelFormat.isExampleRow(group, meter)) continue
                    val reading = parseNumber(grid.cell(r, readingCol)) ?: continue
                    val period = dateCol?.let { grid.cell(r, it) }.orEmpty().ifBlank {
                        formatPeriodLabelForDisplay(grid.cell(r, readingCol))
                    }
                    readings += ParsedMeterReading(
                        groupName = group,
                        meterName = meter,
                        periodLabel = period,
                        readingValue = reading,
                        consumption = consCol?.let { parseNumber(grid.cell(r, it)) },
                    )
                }
            }
        }
        val verifications = catalog
            .filter { it.verificationLabel.isNotBlank() }
            .map { ParsedMeterVerification(it.meterName, it.verificationLabel, it.verificationEpochDay) }
        return ParsedMetersOnly(catalog, readings, verifications)
    }

    private fun looksLikeSectionTitle(value: String): Boolean {
        val t = value.trim()
        return t.equals(MeterExcelFormat.SECTION_CATALOG, true) ||
            t.equals(MeterExcelFormat.SECTION_READINGS, true)
    }

    private fun findHeaderRow(
        grid: SheetGrid,
        required: List<String>,
        extra: (Int, List<String>) -> Boolean = { _, _ -> true },
    ): Int? {
        for (r in 0 until grid.rowCount) {
            val cells = grid.rows[r].map { normalizeHeader(it) }
            if (required.all { need -> cells.any { it.contains(need) } } && extra(r, cells)) {
                return r
            }
        }
        return null
    }

    private fun mapColumns(grid: SheetGrid, headerRow: Int): Map<String, Int> {
        val map = linkedMapOf<String, Int>()
        grid.rows.getOrNull(headerRow).orEmpty().forEachIndexed { col, raw ->
            val key = normalizeHeader(raw)
            if (key.isNotBlank() && key !in map) map[key] = col
        }
        return map
    }

    private fun normalizeHeader(raw: String): String =
        raw.trim().lowercase(Locale.getDefault()).replace("ё", "е").replace("²", "2")

    private fun parseNumber(raw: String): Double? {
        val t = raw.trim().replace(" ", "").replace(',', '.')
        if (t.isEmpty()) return null
        return t.toDoubleOrNull()
    }

    private fun parseCellRef(ref: String): Pair<Int, Int>? {
        val match = Regex("""([A-Z]+)(\d+)""", RegexOption.IGNORE_CASE).matchEntire(ref.trim()) ?: return null
        val col = colLettersToNum(match.groupValues[1])
        val row = match.groupValues[2].toInt() - 1
        return row to col
    }

    private fun colLettersToNum(letters: String): Int {
        var n = 0
        for (ch in letters.uppercase(Locale.US)) {
            n = n * 26 + (ch.code - 'A'.code + 1)
        }
        return n - 1
    }

    private fun newParser(bytes: ByteArray): XmlPullParser {
        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = true
        return factory.newPullParser().apply {
            setInput(ByteArrayInputStream(bytes), "UTF-8")
        }
    }

    private fun attr(parser: XmlPullParser, name: String): String {
        parser.getAttributeValue(null, name)?.let { return it }
        for (i in 0 until parser.attributeCount) {
            if (parser.getAttributeName(i).equals(name, ignoreCase = true)) {
                return parser.getAttributeValue(i).orEmpty()
            }
        }
        return ""
    }
}
