package ru.mybudget.app

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class AttentionRulesTest {
    private val today = LocalDate.of(2026, 10, 21)
    @Test fun achievedGoalIsNotAnAlertEvenIfOverdue() {
        assertFalse(AttentionRules.goalNeedsAction(1000.0, 1000.0, "2026-10-01", today))
    }
    @Test fun lowProgressWithDistantDeadlineIsNotUrgent() {
        assertFalse(AttentionRules.goalNeedsAction(1.0, 1000.0, "2027-10-21", today))
    }
    @Test fun incompleteDueAndOverdueGoalsRequireAction() {
        assertTrue(AttentionRules.goalNeedsAction(1.0, 1000.0, "2026-10-20", today))
        assertTrue(AttentionRules.goalNeedsAction(1.0, 1000.0, "2026-10-27", today))
        assertFalse(AttentionRules.goalNeedsAction(1.0, 1000.0, "2026-10-28", today))
    }
}
