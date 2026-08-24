package ru.mybudget.app.utilities

object MeterExcelFormat {
    const val SHEET_NAME = "счетчики"
    const val COMMUNAL_SHEET_NAME = "коммуналка"
    const val XLSX_MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    val XLSX_OPEN_MIME_TYPES = arrayOf(
        XLSX_MIME,
        "application/vnd.ms-excel",
        "application/octet-stream",
        "*/*",
    )
    const val COL_GROUP = "Группа"
    const val COL_METER = "Счётчик"
    const val COL_VERIFICATION = "Поверка до"
    const val COL_DATE = "Дата"
    const val COL_READING = "Показание"
    const val COL_CONSUMPTION = "Расход"
    const val SECTION_CATALOG = "Каталог счётчиков"
    const val SECTION_READINGS = "Показания"
    const val EXAMPLE_MARKER = "(пример)"

    fun isExampleRow(group: String, meter: String): Boolean {
        return group.contains(EXAMPLE_MARKER, ignoreCase = true) ||
            meter.contains(EXAMPLE_MARKER, ignoreCase = true)
    }
}
