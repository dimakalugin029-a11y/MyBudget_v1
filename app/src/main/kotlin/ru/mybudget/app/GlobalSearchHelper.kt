package ru.mybudget.app

import ru.mybudget.app.data.BudgetCategoryEntity
import ru.mybudget.app.data.BudgetDao
import ru.mybudget.app.data.PlannedObligationEntity
import ru.mybudget.app.data.TransactionEntity
import ru.mybudget.app.data.UtilityBillEntity
import ru.mybudget.app.data.UtilityDao
import ru.mybudget.app.data.UtilityPropertyEntity
import java.text.SimpleDateFormat
import java.util.Locale

object GlobalSearchHelper {
    enum class ResultType {
        TRANSACTION,
        CATEGORY,
        OBLIGATION,
        UTILITY_MONTH,
    }

    data class SearchResult(
        val type: ResultType,
        val title: String,
        val subtitle: String,
        val transactionId: Int? = null,
        val categoryIds: IntArray? = null,
        val obligationId: Int? = null,
        val utilityBillId: Int? = null,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as SearchResult
            return type == other.type && title == other.title && subtitle == other.subtitle
        }

        override fun hashCode(): Int = type.hashCode() * 31 + title.hashCode()
    }

    private val monthLabelFormat = SimpleDateFormat("LLLL yyyy", Locale("ru"))

    suspend fun search(
        query: String,
        budgetId: Int,
        budgetDao: BudgetDao,
        utilityDao: UtilityDao,
        limitPerType: Int = 15,
    ): List<SearchResult> {
        val normalized = query.trim()
        if (normalized.length < 2) return emptyList()

        val needle = normalized.lowercase(Locale.getDefault())
        val results = mutableListOf<SearchResult>()

        budgetDao.searchTransactions(needle, limitPerType).forEach { tx ->
            results += tx.toSearchResult()
        }

        budgetDao.searchCategories(budgetId, needle, limitPerType).forEach { category ->
            results += category.toSearchResult()
        }

        budgetDao.searchObligations(budgetId, needle, limitPerType).forEach { obligation ->
            results += obligation.toSearchResult()
        }

        val propertyNames = utilityDao.getAllProperties().associate { it.id to it.name }
        utilityDao.getAllBills()
            .filter { bill -> billMatches(bill, needle, propertyNames[bill.propertyId].orEmpty()) }
            .take(limitPerType)
            .forEach { bill ->
                results += bill.toSearchResult(propertyNames[bill.propertyId].orEmpty())
            }

        return results
    }

    private fun billMatches(bill: UtilityBillEntity, needle: String, propertyName: String): Boolean {
        val monthLabel = monthLabelFormat.format(
            java.util.Calendar.getInstance().apply {
                set(java.util.Calendar.YEAR, bill.year)
                set(java.util.Calendar.MONTH, bill.month - 1)
                set(java.util.Calendar.DAY_OF_MONTH, 1)
            }.time,
        ).lowercase(Locale.getDefault())
        return monthLabel.contains(needle) ||
            propertyName.lowercase(Locale.getDefault()).contains(needle) ||
            "${bill.year}-${bill.month}".contains(needle)
    }

    private fun TransactionEntity.toSearchResult(): SearchResult {
        val dateLabel = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()).format(date)
        val sign = if (type == "income") "+" else "−"
        return SearchResult(
            type = ResultType.TRANSACTION,
            title = description.ifBlank { dateLabel },
            subtitle = "$dateLabel · $sign${MoneyFormat.formatRub(amount)}",
            transactionId = id,
        )
    }

    private fun BudgetCategoryEntity.toSearchResult(): SearchResult = SearchResult(
        type = ResultType.CATEGORY,
        title = name,
        subtitle = "Статья бюджета",
        categoryIds = intArrayOf(id),
    )

    private fun PlannedObligationEntity.toSearchResult(): SearchResult = SearchResult(
        type = ResultType.OBLIGATION,
        title = name,
        subtitle = MoneyFormat.formatRub(amount),
        obligationId = id,
    )

    private fun UtilityBillEntity.toSearchResult(propertyName: String): SearchResult {
        val monthLabel = monthLabelFormat.format(
            java.util.Calendar.getInstance().apply {
                set(java.util.Calendar.YEAR, year)
                set(java.util.Calendar.MONTH, month - 1)
                set(java.util.Calendar.DAY_OF_MONTH, 1)
            }.time,
        )
        return SearchResult(
            type = ResultType.UTILITY_MONTH,
            title = monthLabel.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale("ru")) else it.toString() },
            subtitle = propertyName.ifBlank { "Коммуналка" },
            utilityBillId = id,
        )
    }
}
