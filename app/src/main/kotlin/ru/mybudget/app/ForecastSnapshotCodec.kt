package ru.mybudget.app

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken

object ForecastSnapshotCodec {
    data class IncomeLineDto(
        @SerializedName("id") val sourceId: Int,
        @SerializedName("name") val name: String,
        @SerializedName("amount") val amount: Double,
    )

    data class ExpenseLineDto(
        @SerializedName("id") val categoryId: Int,
        @SerializedName("name") val name: String,
        @SerializedName("amount") val amount: Double,
        @SerializedName("source") val source: String,
    )

    data class LinesDto(
        @SerializedName("incomes") val incomes: List<IncomeLineDto>,
        @SerializedName("expenses") val expenses: List<ExpenseLineDto>,
    )

    private val gson = Gson()
    private val type = object : TypeToken<LinesDto>() {}.type

    fun encode(month: ForecastHelper.ForecastMonth): String {
        val dto = LinesDto(
            incomes = month.incomeLines.map {
                IncomeLineDto(it.sourceId, it.name, it.amount)
            },
            expenses = month.expenseLines.map {
                ExpenseLineDto(it.categoryId, it.name, it.amount, it.source.name)
            },
        )
        return gson.toJson(dto)
    }

    fun decode(json: String): LinesDto? {
        if (json.isBlank()) return null
        return runCatching { gson.fromJson<LinesDto>(json, type) }.getOrNull()
    }
}
