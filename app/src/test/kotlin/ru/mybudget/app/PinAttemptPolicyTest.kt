package ru.mybudget.app

import org.junit.Assert.*
import org.junit.Test
import ru.mybudget.app.security.PinAttemptPolicy as Policy

class PinAttemptPolicyTest {
    @Test fun delayEscalatesAndCaps() {
        assertEquals(listOf(0L,0L,0L,0L,30000L,60000L,120000L,240000L,300000L,300000L), (1..10).map(Policy::delay))
    }
    @Test fun sameBootUsesMonotonicClockDespiteWallChanges() {
        val state=Policy.State(5,50000,40000,2)
        assertEquals(30000L,Policy.remaining(state,Long.MAX_VALUE,10000,2))
        assertEquals(0L,Policy.remaining(state,0,40001,2))
    }
    @Test fun rebootUsesWallDeadlineAndClampsRollback() {
        val state=Policy.State(5,50000,40000,2)
        assertEquals(40000L,Policy.remaining(state,10000,0,3))
        assertEquals(300000L,Policy.remaining(state,-1000000,0,3))
        assertEquals(0L,Policy.remaining(state,50001,0,3))
    }
    @Test fun fifthReservationPersistsDelayAndCounter() {
        val state=Policy.reserve(Policy.State(failures=4),1000,2000,7)
        assertEquals(5,state.failures)
        assertEquals(31000L,state.wallDeadline)
        assertEquals(32000L,state.elapsedDeadline)
        assertEquals(29999L,Policy.remaining(state.copy(),1001,2001,7))
    }
    @Test fun counterCannotOverflow() {
        val state=Policy.reserve(Policy.State(failures=Int.MAX_VALUE),1,2,3)
        assertEquals(Int.MAX_VALUE,state.failures)
        assertEquals(300000L,Policy.remaining(state,1,2,3))
    }
    @Test fun emptyStateDoesNotBlock() {
        assertEquals(0L,Policy.remaining(Policy.State(),100,100,1))
        assertEquals(0L,Policy.delay(-1))
    }
}
