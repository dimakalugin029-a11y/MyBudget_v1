package ru.mybudget.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WhatIfScenarioHelperTest {
    @Test
    fun simulateGoalWithExtra_computesMonthsToComplete() {
        val result = WhatIfScenarioHelper.simulateGoalWithExtra(
            goalName = "Отпуск",
            currentBalance = 20_000.0,
            targetAmount = 50_000.0,
            extraPerMonth = 5_000.0,
            daysUntilDeadline = 180,
        )

        assertEquals(30_000.0, result.remaining, 0.01)
        assertEquals(5_000.0, result.extraPerMonth, 0.01)
        assertEquals(3, result.monthsToComplete)
        assertEquals("Отпуск", result.goalName)
    }

    @Test
    fun simulateGoalWithExtra_returnsNullMonthsWhenNoContribution() {
        val result = WhatIfScenarioHelper.simulateGoalWithExtra(
            goalName = "Ремонт",
            currentBalance = 0.0,
            targetAmount = 100_000.0,
            extraPerMonth = 0.0,
            daysUntilDeadline = null,
        )

        assertNull(result.monthsToComplete)
    }

    @Test
    fun simulateObligationShiftByMonth_freesCurrentMonth() {
        val result = WhatIfScenarioHelper.simulateObligationShiftByMonth(
            obligationName = "Кредит",
            amount = 12_000.0,
            nextMonthLabel = "октябрь 2026",
        )

        assertEquals(12_000.0, result.freedThisMonth, 0.01)
        assertEquals("октябрь 2026", result.dueNextMonthLabel)
    }
}
