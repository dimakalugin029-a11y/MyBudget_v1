package ru.mybudget.app

import org.junit.Assert.*
import org.junit.Test
import ru.mybudget.app.data.BalanceMovementEntity
import java.time.LocalDate
import java.time.ZoneId

class LedgerBalanceHelperTest {
    private fun at(day: Int, hour: Int = 0) = LocalDate.of(2026,10,day).atTime(hour,0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    private fun row(id: Long, value: Double, time: Long, kind: String = "adjustment", physical: Double = value) =
        BalanceMovementEntity(id, 1, 1, physical, value, time, kind, "test")
    @Test fun childAndParentAreNotCountedTwice() {
        val rows = listOf(row(1,100.0,at(10),"opening"), row(2,0.0,at(10),"opening",100.0), row(3,-20.0,at(10,12)))
        val result = LedgerBalanceHelper.build(rows,at(10),at(10),at(11),at(12))
        assertEquals(100.0,result.startBalance!!,0.0)
        assertEquals(80.0,result.endBalance!!,0.0)
    }
    @Test fun transferAndReversalChangeBalanceWithoutChangingFactTransactions() {
        val rows = listOf(row(1,100.0,at(10),"opening"),row(2,-30.0,at(11),"transfer"),row(3,30.0,at(11),"transfer"),row(4,20.0,at(12),"reversal"))
        assertEquals(120.0,LedgerBalanceHelper.build(rows,at(10),at(10),at(13),at(14)).endBalance!!,0.0)
    }
    @Test fun rangeBeforeCoverageNeverInventsHistory() {
        assertTrue(LedgerBalanceHelper.build(listOf(row(1,100.0,at(10),"opening")),at(10),at(1),at(9),at(12)).points.isEmpty())
    }
    @Test fun rangeCrossingCoverageHasUnknownOpeningAndOnlyKnownDays() {
        val result=LedgerBalanceHelper.build(listOf(row(1,100.0,at(10,12),"opening")),at(10,12),at(1),at(12),at(13))
        assertNull(result.startBalance)
        assertEquals(2,result.points.size)
        assertEquals(LocalDate.of(2026,10,10).toEpochDay(),result.points.first().dayKey)
        assertEquals(100.0,result.endBalance!!,0.0)
    }
    @Test fun halfOpenRangeExcludesFollowingDay() {
        val result=LedgerBalanceHelper.build(listOf(row(1,100.0,at(10),"opening"),row(2,-20.0,at(11))),at(10),at(10),at(11),at(12))
        assertEquals(100.0,result.endBalance!!,0.0)
    }
    @Test fun openingIncludesPriorChanges() {
        val result=LedgerBalanceHelper.build(listOf(row(1,100.0,at(10),"opening"),row(2,-20.0,at(11))),at(10),at(12),at(13),at(14))
        assertEquals(80.0,result.startBalance!!,0.0)
        assertEquals(80.0,result.endBalance!!,0.0)
    }
    @Test fun futureAndUnknownCoverageAreNotPresentedAsZeroHistory() {
        assertTrue(LedgerBalanceHelper.build(emptyList(),null,at(10),at(11),at(12)).points.isEmpty())
        assertTrue(LedgerBalanceHelper.build(emptyList(),at(10),at(13),at(14),at(12)).points.isEmpty())
    }
    @Test fun currentDayStopsAtCapturedTime() {
        val result=LedgerBalanceHelper.build(listOf(row(1,100.0,at(10),"opening"),row(2,-20.0,at(12,18))),at(10),at(10),at(15),at(12,12))
        assertEquals(3,result.points.size)
        assertEquals(100.0,result.endBalance!!,0.0)
    }
}
