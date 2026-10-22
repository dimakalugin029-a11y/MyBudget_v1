package ru.mybudget.app.security

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import ru.mybudget.app.BudgetApplication
import ru.mybudget.app.setup.AppLockPreferences

object PinAttemptController {
    private val mutex=Mutex()
    private const val FAILURES="pin_attempt_failures"
    private const val WALL="pin_attempt_wall_deadline"
    private const val ELAPSED="pin_attempt_elapsed_deadline"
    private const val BOOT="pin_attempt_boot"
    sealed interface Result {
        data object Verified: Result
        data object Wrong: Result
        data object TooShort: Result
        data object Locked: Result
        data object Unavailable: Result
    }
    fun clear(editor: SharedPreferences.Editor): SharedPreferences.Editor =
        editor.remove(FAILURES).remove(WALL).remove(ELAPSED).remove(BOOT)
    private fun prefs(context:Context)=context.getSharedPreferences(BudgetApplication.PREFS_NAME,Context.MODE_PRIVATE)
    private fun read(p:SharedPreferences)=PinAttemptPolicy.State(p.getInt(FAILURES,0),p.getLong(WALL,0),p.getLong(ELAPSED,0),p.getInt(BOOT,-1))
    private fun boot(context:Context)=Settings.Global.getInt(context.contentResolver,Settings.Global.BOOT_COUNT,-1)
    private fun write(p:SharedPreferences,s:PinAttemptPolicy.State):Boolean=p.edit().putInt(FAILURES,s.failures)
        .putLong(WALL,s.wallDeadline).putLong(ELAPSED,s.elapsedDeadline).putInt(BOOT,s.boot).commit()
    private fun remainingLocked(context:Context):Long {
        val p=prefs(context);val state=read(p);val boot=boot(context)
        val wall=System.currentTimeMillis();val elapsed=SystemClock.elapsedRealtime()
        val remaining=PinAttemptPolicy.remaining(state,wall,elapsed,boot)
        if (state.failures>0 && ((state.boot!=boot && boot>=0) || (boot<0 && state.wallDeadline-wall>PinAttemptPolicy.MAX_DELAY_MS))) {
            check(write(p,state.copy(wallDeadline=wall+remaining,elapsedDeadline=elapsed+remaining,boot=boot)))
        }
        return remaining
    }
    suspend fun remaining(context:Context):Long=withContext(Dispatchers.IO) { mutex.withLock { remainingLocked(context) } }
    suspend fun verify(context:Context,pin:String):Result=withContext(Dispatchers.Default) {
        mutex.withLock {
            try {
                if (remainingLocked(context)>0) return@withLock Result.Locked
                if (pin.length<4) return@withLock Result.TooShort
                val p=prefs(context)
                // Reserve before hashing: killing/restarting the process cannot erase this attempt.
                check(write(p,PinAttemptPolicy.reserve(read(p),System.currentTimeMillis(),SystemClock.elapsedRealtime(),boot(context))))
                if (AppLockPreferences.verifyPin(context,pin)) {
                    check(AppLockPreferences.markUnlocked(context,durable=true))
                    Result.Verified
                } else Result.Wrong
            } catch (cancelled:kotlinx.coroutines.CancellationException) { throw cancelled
            } catch (_:Exception) { Result.Unavailable }
        }
    }
    suspend fun biometricVerified(context:Context)=withContext(Dispatchers.IO) {
        mutex.withLock { check(AppLockPreferences.markUnlocked(context,durable=true)) }
    }
}
