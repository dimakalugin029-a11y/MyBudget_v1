package ru.mybudget.app.utilities

import android.content.Context
import ru.mybudget.app.MoneyFormat
import ru.mybudget.app.R
import ru.mybudget.app.data.UtilityBillEntity
import ru.mybudget.app.data.UtilityDao
import ru.mybudget.app.data.UtilityLineItemEntity
import ru.mybudget.app.data.UtilitySectionEntity
import ru.mybudget.app.data.UtilityTariffEntity
import ru.mybudget.app.data.UtilityTariffRow
import ru.mybudget.app.data.UtilityTemplateLineEntity
import ru.mybudget.app.data.UtilityTemplateSectionEntity
import ru.mybudget.app.data.UtilityTemplateSectionWithLines
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

object UtilityUserTemplate {
    const val LINE_QTY_TARIFF = UtilityTemplateLineEntity.LINE_MODE_QTY_TARIFF
    const val LINE_AMOUNT_ONLY = UtilityTemplateLineEntity.LINE_MODE_AMOUNT_ONLY
    const val PAYMENT_DESCRIPTION_PREFIX = "Коммуналка "

    private val RU = Locale("ru")

    fun formatPeriod(year: Int, month: Int): String {
        val cal = Calendar.getInstance().apply {
            set(Calendar.YEAR, year)
            set(Calendar.MONTH, month - 1)
            set(Calendar.DAY_OF_MONTH, 1)
        }
        return SimpleDateFormat("LLLL yyyy", RU).format(cal.time)
    }

    fun computedAmount(quantity: Double?, tariff: Double?): Double? {
        if (quantity == null || tariff == null) return null
        return MoneyFormat.roundMoney(quantity * tariff)
    }

    fun formatAreaLine(context: Context, area: Double): String {
        return if (area > 0.0) {
            context.getString(R.string.utility_bill_area_value, area)
        } else {
            context.getString(R.string.utility_bill_area_not_set)
        }
    }

    fun formatBillHeaderSubtitle(context: Context, area: Double): String {
        return formatAreaLine(context, area) + "\n" + context.getString(R.string.utility_bill_lines_hint)
    }

    fun paymentDescription(year: Int, month: Int): String {
        return PAYMENT_DESCRIPTION_PREFIX + formatPeriod(year, month)
    }

    fun titlePeriod(year: Int, month: Int): String {
        val period = formatPeriod(year, month)
        if (period.isEmpty()) return period
        return period.replaceFirstChar { ch ->
            if (ch.isLowerCase()) ch.titlecase(RU) else ch.toString()
        }
    }

    suspend fun loadBillDetail(dao: UtilityDao, billId: Int): UtilityBillDetail? {
        val bill = dao.getBillById(billId) ?: return null
        val sections = dao.getSectionsForBill(billId).map { section ->
            UtilitySectionWithLines(section, dao.getLineItemsForSection(section.id))
        }
        return UtilityBillDetail(bill, sections)
    }

    suspend fun clearAllData(dao: UtilityDao) {
        dao.deleteAllMeterInfo()
        dao.deleteAllMeterReadings()
        dao.deleteAllTariffs()
        dao.deleteAllTemplateLines()
        dao.deleteAllTemplateSections()
        dao.deleteAllLineItems()
        dao.deleteAllSections()
        dao.deleteAllBills()
    }

    suspend fun getTemplateWithLines(dao: UtilityDao): List<UtilityTemplateSectionWithLines> {
        return dao.getAllTemplateSections().map { section ->
            UtilityTemplateSectionWithLines(section, dao.getTemplateLinesForSection(section.id))
        }
    }

    suspend fun getTariffRows(dao: UtilityDao): List<UtilityTariffRow> {
        val rows = mutableListOf<UtilityTariffRow>()
        for (section in dao.getAllTemplateSections()) {
            for (line in dao.getTemplateLinesForSection(section.id)) {
                if (line.lineMode != LINE_QTY_TARIFF) continue
                rows += UtilityTariffRow(
                    line = line,
                    sectionName = section.name,
                    tariff = dao.getTariffForLine(line.id)?.tariff,
                )
            }
        }
        return rows
    }

    suspend fun addSection(dao: UtilityDao, name: String): Long {
        val sort = dao.getMaxTemplateSectionSortOrder() + 1
        return dao.insertTemplateSection(UtilityTemplateSectionEntity(name = name, sortOrder = sort))
    }

    suspend fun addLine(
        dao: UtilityDao,
        sectionId: Int,
        name: String,
        groupLabel: String,
        lineMode: String,
    ): Long {
        val sort = dao.getMaxTemplateLineSortOrder(sectionId) + 1
        return dao.insertTemplateLine(
            UtilityTemplateLineEntity(
                sectionId = sectionId,
                groupLabel = groupLabel,
                name = name,
                lineMode = lineMode,
                sortOrder = sort,
            ),
        )
    }

    suspend fun setTariff(dao: UtilityDao, templateLineId: Int, tariff: Double?) {
        if (tariff == null || tariff <= 0.0) {
            dao.deleteTariffForLine(templateLineId)
            return
        }
        val rounded = MoneyFormat.roundMoney(tariff)
        val existing = dao.getTariffForLine(templateLineId)
        dao.upsertTariff(
            existing?.copy(tariff = rounded, updatedAt = System.currentTimeMillis())
                ?: UtilityTariffEntity(templateLineId = templateLineId, tariff = rounded),
        )
    }

    suspend fun createBillFromUserTemplate(
        dao: UtilityDao,
        year: Int,
        month: Int,
        apartmentArea: Double = 0.0,
        applyTariffs: Boolean = true,
    ): Int {
        dao.getBillByPeriod(year, month)?.let { return it.id }
        val billId = dao.insertBill(
            UtilityBillEntity(year = year, month = month, apartmentArea = apartmentArea),
        ).toInt()
        val tariffs = if (applyTariffs) {
            dao.getAllTariffs().associate { it.templateLineId to it.tariff }
        } else {
            emptyMap()
        }
        for (section in dao.getAllTemplateSections()) {
            val sectionId = dao.insertSection(
                UtilitySectionEntity(
                    billId = billId,
                    name = section.name,
                    sortOrder = section.sortOrder,
                ),
            ).toInt()
            for (line in dao.getTemplateLinesForSection(section.id)) {
                val tariff = if (line.lineMode == LINE_QTY_TARIFF) tariffs[line.id] else null
                dao.insertLineItem(
                    UtilityLineItemEntity(
                        sectionId = sectionId,
                        groupLabel = line.groupLabel,
                        name = line.name,
                        quantity = null,
                        tariff = tariff,
                        amount = 0.0,
                        sortOrder = line.sortOrder,
                    ),
                )
            }
        }
        return billId
    }

    suspend fun copyBillFromPreviousMonth(
        dao: UtilityDao,
        targetYear: Int,
        targetMonth: Int,
        copyAmounts: Boolean,
    ): Int? {
        dao.getBillByPeriod(targetYear, targetMonth)?.let { return it.id }
        val previous = dao.getAllBills()
            .filter { it.year < targetYear || (it.year == targetYear && it.month < targetMonth) }
            .maxWithOrNull(compareBy({ it.year }, { it.month }))
            ?: return null
        val detail = loadBillDetail(dao, previous.id) ?: return null
        val billId = dao.insertBill(
            UtilityBillEntity(
                year = targetYear,
                month = targetMonth,
                apartmentArea = previous.apartmentArea,
            ),
        ).toInt()
        for (sectionWithLines in detail.sections) {
            val sectionId = dao.insertSection(
                UtilitySectionEntity(
                    billId = billId,
                    name = sectionWithLines.section.name,
                    sortOrder = sectionWithLines.section.sortOrder,
                ),
            ).toInt()
            for (line in sectionWithLines.lines) {
                val quantity = if (copyAmounts) line.quantity else null
                val tariff = line.tariff
                val amount = if (copyAmounts) {
                    line.amount
                } else {
                    computedAmount(quantity, tariff) ?: 0.0
                }
                dao.insertLineItem(
                    UtilityLineItemEntity(
                        sectionId = sectionId,
                        groupLabel = line.groupLabel,
                        name = line.name,
                        quantity = quantity,
                        tariff = tariff,
                        amount = amount,
                        sortOrder = line.sortOrder,
                    ),
                )
            }
        }
        return billId
    }
}

data class UtilitySectionWithLines(
    val section: UtilitySectionEntity,
    val lines: List<UtilityLineItemEntity>,
)

data class UtilityBillDetail(
    val bill: UtilityBillEntity,
    val sections: List<UtilitySectionWithLines>,
) {
    val grandTotal: Double get() = sections.sumOf { it.lines.sumOf { line -> line.amount } }
}
