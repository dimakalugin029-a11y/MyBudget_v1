package ru.mybudget.app

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import ru.mybudget.app.data.UtilityBillEntity
import ru.mybudget.app.data.UtilityLineItemEntity
import ru.mybudget.app.data.UtilityMeterReadingEntity
import ru.mybudget.app.data.UtilitySectionEntity
import ru.mybudget.app.utilities.UtilityMeterBillLinker
import ru.mybudget.app.utilities.UtilityUserTemplate
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class UtilitiesFlowTest {
    companion object {
        private lateinit var seed: ScreenTestSeed.SeedData

        @BeforeClass
        @JvmStatic
        fun prepareApp() {
            val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
            seed = ScreenTestSeed.prepare(context)
        }
    }

    @Test
    fun bill_metersApply_payFromBudget() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val manager = BudgetManager.getInstance(context)
        val dao = manager.utilityDao
        val year = 2026
        val month = 8

        val billId = dao.insertBill(UtilityBillEntity(year = year, month = month, apartmentArea = 52.0)).toInt()
        val sectionId = dao.insertSection(
            UtilitySectionEntity(billId = billId, name = "Вода", sortOrder = 0),
        ).toInt()
        dao.insertLineItem(
            UtilityLineItemEntity(
                sectionId = sectionId,
                name = "Холодная вода",
                quantity = null,
                tariff = 45.0,
                amount = 0.0,
            ),
        )
        dao.insertMeterReading(
            UtilityMeterReadingEntity(
                groupName = "Вода",
                meterName = "Холодная вода",
                periodLabel = "01.08.2026",
                readingValue = 120.0,
                consumption = 5.0,
            ),
        )

        val applyResult = UtilityMeterBillLinker.applyMeterReadingsToBill(dao, billId)
        assertTrue(applyResult.updatedLines > 0)

        val lineAfterApply = dao.getLineItemsForBill(billId).first()
        assertTrue(lineAfterApply.quantity != null && lineAfterApply.quantity!! > 0.0)
        assertTrue(lineAfterApply.amount > 0.0)

        val leafCategory = manager.getCategoriesAsync(forceReload = true)
            .first { it.parentId != 0 }
        manager.recordTransaction(leafCategory.id, 10_000.0, "income", "utilities-flow-test")

        val bill = dao.getBillById(billId)!!
        val propertyName = dao.getPropertyById(bill.propertyId)?.name.orEmpty()
        val total = dao.getLineItemsForBill(billId).sumOf { it.amount }
        val description = UtilityUserTemplate.paymentDescription(propertyName, bill.year, bill.month)
        val groupId = UUID.randomUUID().toString()
        manager.repository.applyTransactionGroup(
            items = listOf(leafCategory.id to total),
            type = "expense",
            description = description,
            groupId = groupId,
        )
        manager.reloadCategoriesFromDatabase()
        dao.updateBill(
            bill.copy(
                budgetPaidAt = System.currentTimeMillis(),
                budgetPaymentSummary = "${leafCategory.name}: ${MoneyFormat.formatRub(total)}",
                budgetPaymentGroupId = groupId,
            ),
        )

        val paid = dao.getBillById(billId)!!
        assertNotNull(paid.budgetPaidAt)
        assertTrue(paid.budgetPaymentSummary.isNotBlank())
    }
}
