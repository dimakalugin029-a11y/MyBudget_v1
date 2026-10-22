package ru.mybudget.app.security

object PinAttemptPolicy {
    const val MAX_DELAY_MS = 300_000L
    data class State(val failures: Int=0, val wallDeadline: Long=0, val elapsedDeadline: Long=0, val boot: Int=-1)
    fun delay(failures: Int): Long = when {
        failures < 5 -> 0
        failures == 5 -> 30_000
        failures == 6 -> 60_000
        failures == 7 -> 120_000
        failures == 8 -> 240_000
        else -> MAX_DELAY_MS
    }
    fun remaining(state: State, wall: Long, elapsed: Long, boot: Int): Long =
        (if (state.boot==boot && boot>=0) state.elapsedDeadline-elapsed else state.wallDeadline-wall).coerceIn(0,MAX_DELAY_MS)
    fun reserve(state: State, wall: Long, elapsed: Long, boot: Int): State {
        val failures=state.failures.coerceIn(0,Int.MAX_VALUE-1)+1
        val delay=delay(failures)
        return State(failures,wall+delay,elapsed+delay,boot)
    }
}
