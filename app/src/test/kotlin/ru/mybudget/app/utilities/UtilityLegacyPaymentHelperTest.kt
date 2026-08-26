package ru.mybudget.app.utilities

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mybudget.app.data.UtilityBillEntity
import java.time.YearMonth

class UtilityLegacyPaymentHelperTest {
    @Test
    fun isEligibleForLegacyMark_onlyPastMonthsWithAmount() {
        val current = YearMonth.of(2026, 8)
        val past = UtilityBillEntity(id = 1, year = 2026, month = 7, apartmentArea = 50.0)
        val currentMonth = UtilityBillEntity(id = 2, year = 2026, month = 8, apartmentArea = 50.0)
        val paid = past.copy(budgetPaidAt = 1L)

        assertTrue(UtilityLegacyPaymentHelper.isEligibleForLegacyMark(past, 1000.0, current))
        assertFalse(UtilityLegacyPaymentHelper.isEligibleForLegacyMark(currentMonth, 1000.0, current))
        assertFalse(UtilityLegacyPaymentHelper.isEligibleForLegacyMark(paid, 1000.0, current))
        assertFalse(UtilityLegacyPaymentHelper.isEligibleForLegacyMark(past, 0.0, current))
    }

    @Test
    fun isLegacyPaid_detectsMarkerGroupId() {
        val bill = UtilityBillEntity(
            id = 1,
            year = 2025,
            month = 1,
            apartmentArea = 0.0,
            budgetPaidAt = 1L,
            budgetPaymentGroupId = UtilityLegacyPaymentHelper.LEGACY_GROUP_ID,
        )
        assertTrue(UtilityLegacyPaymentHelper.isLegacyPaid(bill))
    }
}
