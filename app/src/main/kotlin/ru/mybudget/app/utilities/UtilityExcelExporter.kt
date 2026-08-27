package ru.mybudget.app.utilities

import ru.mybudget.app.data.UtilityDao
import java.io.OutputStream
import java.time.LocalDate
import java.time.format.DateTimeFormatter

object UtilityExcelExporter {
    private val fileDateFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    fun suggestedMetersFileName(): String =
        "MyBudget_schetchiki_${LocalDate.now().format(fileDateFmt)}.xlsx"

    fun suggestedMetersTemplateFileName(): String =
        "MyBudget_schetchiki_shablon_${LocalDate.now().format(fileDateFmt)}.xlsx"

    fun suggestedCommunalFileName(): String =
        "MyBudget_kommunal_${LocalDate.now().format(fileDateFmt)}.xlsx"

    fun exportMetersTemplate(output: OutputStream) {
        val rows = mutableListOf<List<CellValue>>()
        rows += textRow("Шаблон счётчиков MyBudget")
        rows += textRow(
            "Заполните таблицы ниже. Не меняйте заголовки столбцов. Дата — дд.мм.гггг. Строки с «(пример)» удалите или замените своими данными.",
        )
        rows += textRow("")
        rows += textRow(MeterExcelFormat.SECTION_CATALOG)
        rows += textRow(MeterExcelFormat.COL_GROUP, MeterExcelFormat.COL_METER, MeterExcelFormat.COL_VERIFICATION)
        rows += textRow("Кухня", "ХВС 12345 (пример)", "31.12.2028")
        rows += textRow("Ванная", "ГВС 67890 (пример)", "")
        rows += textRow("")
        rows += textRow(MeterExcelFormat.SECTION_READINGS)
        rows += textRow(
            MeterExcelFormat.COL_GROUP,
            MeterExcelFormat.COL_METER,
            MeterExcelFormat.COL_DATE,
            MeterExcelFormat.COL_READING,
            MeterExcelFormat.COL_CONSUMPTION,
        )
        rows += listOf(
            CellValue.Text("Кухня"),
            CellValue.Text("ХВС 12345 (пример)"),
            CellValue.Text("01.01.2024"),
            CellValue.Number(100.5),
            CellValue.Number(5.2),
        )
        rows += listOf(
            CellValue.Text("Кухня"),
            CellValue.Text("ХВС 12345 (пример)"),
            CellValue.Text("01.02.2024"),
            CellValue.Number(105.7),
            CellValue.Number(5.2),
        )
        XlsxWorkbook().apply { addSheet(MeterExcelFormat.SHEET_NAME, rows) }.writeTo(output)
    }

    suspend fun exportMeters(dao: UtilityDao, propertyId: Int, output: OutputStream) {
        XlsxWorkbook().apply { addSheet(MeterExcelFormat.SHEET_NAME, buildMeterRows(dao, propertyId)) }.writeTo(output)
    }

    suspend fun exportCommunal(dao: UtilityDao, propertyId: Int, output: OutputStream) {
        XlsxWorkbook().apply {
            addSheet(MeterExcelFormat.COMMUNAL_SHEET_NAME, buildCommunalRows(dao, propertyId))
            addSheet(MeterExcelFormat.SHEET_NAME, buildMeterRows(dao, propertyId))
        }.writeTo(output)
    }

    private suspend fun buildMeterRows(dao: UtilityDao, propertyId: Int): List<List<CellValue>> {
        val infos = dao.getAllMeterInfo(propertyId)
        val readings = dao.getAllMeterReadings(propertyId).sortedWith(
            compareBy<ru.mybudget.app.data.UtilityMeterReadingEntity> { it.groupName }
                .thenBy { it.meterName }
                .thenByDescending { UtilityExcelParser.readingSortKey(it.periodLabel, it.sortOrder) },
        )
        val catalog = mutableListOf<List<CellValue>>()
        catalog += textRow(MeterExcelFormat.SECTION_CATALOG)
        catalog += textRow(MeterExcelFormat.COL_GROUP, MeterExcelFormat.COL_METER, MeterExcelFormat.COL_VERIFICATION)
        infos.forEach { info ->
            catalog += textRow(
                info.groupName,
                UtilityExcelParser.normalizeMeterName(info.meterName),
                info.verificationDateLabel,
            )
        }
        val readingRows = mutableListOf<List<CellValue>>()
        readingRows += textRow(MeterExcelFormat.SECTION_READINGS)
        readingRows += textRow(
            MeterExcelFormat.COL_GROUP,
            MeterExcelFormat.COL_METER,
            MeterExcelFormat.COL_DATE,
            MeterExcelFormat.COL_READING,
            MeterExcelFormat.COL_CONSUMPTION,
        )
        readings.forEach { reading ->
            readingRows += listOf(
                CellValue.Text(reading.groupName),
                CellValue.Text(UtilityExcelParser.normalizeMeterName(reading.meterName)),
                CellValue.Text(UtilityExcelParser.formatPeriodLabelForDisplay(reading.periodLabel)),
                CellValue.Number(reading.readingValue),
                reading.consumption?.let { CellValue.Number(it) } ?: CellValue.Empty,
            )
        }
        return catalog + listOf(listOf(CellValue.Empty)) + readingRows
    }

    private suspend fun buildCommunalRows(dao: UtilityDao, propertyId: Int): List<List<CellValue>> {
        val bills = dao.getAllBills(propertyId).sortedWith(compareBy({ it.year }, { it.month }))
        val sections = dao.getAllSectionsForExport()
        val lines = dao.getAllLineItemsForExport()
        val rows = mutableListOf<List<CellValue>>()
        rows += textRow("Коммуналка (MyBudget)")
        rows += textRow("Год", "Месяц", "Площадь м²", "Раздел", MeterExcelFormat.COL_GROUP, "Услуга", "Кол-во", "Тариф", "Сумма")
        bills.forEach { bill ->
            val billSections = sections.filter { it.billId == bill.id }.sortedBy { it.sortOrder }
            billSections.forEach { section ->
                val sectionLines = lines.filter { it.sectionId == section.id }.sortedBy { it.sortOrder }
                if (sectionLines.isEmpty()) {
                    rows += listOf(
                        CellValue.Number(bill.year.toDouble()),
                        CellValue.Number(bill.month.toDouble()),
                        CellValue.Number(bill.apartmentArea),
                        CellValue.Text(section.name),
                        CellValue.Empty,
                        CellValue.Empty,
                        CellValue.Empty,
                        CellValue.Empty,
                        CellValue.Empty,
                    )
                } else {
                    sectionLines.forEach { line ->
                        rows += listOf(
                            CellValue.Number(bill.year.toDouble()),
                            CellValue.Number(bill.month.toDouble()),
                            CellValue.Number(bill.apartmentArea),
                            CellValue.Text(section.name),
                            CellValue.Text(line.groupLabel),
                            CellValue.Text(line.name),
                            line.quantity?.let { CellValue.Number(it) } ?: CellValue.Empty,
                            line.tariff?.let { CellValue.Number(it) } ?: CellValue.Empty,
                            CellValue.Number(line.amount),
                        )
                    }
                }
            }
        }
        return rows
    }

    private fun textRow(vararg texts: String): List<CellValue> = texts.map { CellValue.Text(it) }
}
