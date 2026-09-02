package ru.mybudget.app

import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

object MoneyFormat {
    const val QUANTITY_DECIMALS = 6

    private val symbols = DecimalFormatSymbols(Locale("ru", "RU")).apply {
        decimalSeparator = ','
        groupingSeparator = ' '
    }
    private val formatter = DecimalFormat("0.00", symbols)
    private val quantityFormatter = DecimalFormat("0.######", symbols)

    fun format(value: Double): String = formatter.format(roundMoney(value))

    fun formatRub(value: Double): String = "${format(value)} ₽"

    /** Compact label for chart Y-axis (e.g. 50 тыс, 1,2 млн). */
    fun formatChartAxis(value: Double): String {
        val abs = kotlin.math.abs(value)
        return when {
            abs >= 1_000_000 -> {
                val millions = value / 1_000_000.0
                String.format(Locale("ru", "RU"), "%.1f млн", millions)
            }
            abs >= 10_000 -> {
                val thousands = value / 1_000.0
                String.format(Locale("ru", "RU"), "%.0f тыс", thousands)
            }
            else -> format(value)
        }
    }

    fun formatQuantity(value: Double): String = quantityFormatter.format(roundQuantity(value))

    fun roundMoney(value: Double): Double = Math.rint(value * 100.0) / 100.0

    fun roundQuantity(value: Double): Double = Math.rint(value * 1_000_000.0) / 1_000_000.0

    fun parse(text: CharSequence?): Double? = parseDecimal(text)?.let { roundMoney(it) }

    fun parseQuantity(text: CharSequence?): Double? = parseDecimal(text)?.let { roundQuantity(it) }

    private fun parseDecimal(text: CharSequence?): Double? {
        if (text.isNullOrBlank()) return null
        val normalized = text.toString().trim()
            .replace(" ", "")
            .replace("\u00A0", "")
            .replace(',', '.')
        return normalized.toDoubleOrNull()
    }
}
