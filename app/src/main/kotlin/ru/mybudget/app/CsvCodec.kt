package ru.mybudget.app

object CsvCodec {
    fun row(values: List<String>): String = values.joinToString(",") { value ->
        if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' })
            "\"${value.replace("\"", "\"\"")}\"" else value
    } + "\n"
}
