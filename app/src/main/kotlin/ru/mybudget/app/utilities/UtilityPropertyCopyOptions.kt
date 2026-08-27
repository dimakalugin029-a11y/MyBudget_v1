package ru.mybudget.app.utilities

data class UtilityPropertyCopyOptions(
    val template: Boolean = false,
    val tariffs: Boolean = false,
    val meters: Boolean = false,
) {
    fun hasAny(): Boolean = template || tariffs || meters
}
