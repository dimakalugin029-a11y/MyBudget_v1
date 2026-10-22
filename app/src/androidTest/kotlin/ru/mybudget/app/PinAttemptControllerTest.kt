package ru.mybudget.app

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import ru.mybudget.app.security.PinAttemptController as Controller
import ru.mybudget.app.setup.AppLockPreferences as Lock

@RunWith(AndroidJUnit4::class)
class PinAttemptControllerTest {
    private lateinit var context:Context
    @Before fun setup() {
        val base=ApplicationProvider.getApplicationContext<Context>()
        val prefix="pin-test-${java.util.UUID.randomUUID()}-"
        context=object:ContextWrapper(base) {
            override fun getApplicationContext():Context=this
            override fun getSharedPreferences(name:String,mode:Int)=base.getSharedPreferences(prefix+name,mode)
        }
        Lock.setPin(context,"1234567890123") // Existing PINs beyond the new setup limit stay usable.
    }
    @After fun clear() { context.getSharedPreferences(BudgetApplication.PREFS_NAME,Context.MODE_PRIVATE).edit().clear().commit() }
    @Test fun shortInputDoesNotConsumeAttemptAndLongExistingPinUnlocks()=runBlocking {
        assertEquals(Controller.Result.TooShort,Controller.verify(context,"12"))
        assertEquals(Controller.Result.Verified,Controller.verify(context,"1234567890123"))
        assertEquals(0L,Controller.remaining(context))
    }
    @Test fun fifthWrongAttemptLocksAndBiometricSuccessClearsPersistedCounter()=runBlocking {
        repeat(5) { assertEquals(Controller.Result.Wrong,Controller.verify(context,"9999")) }
        assertTrue(Controller.remaining(context)>0)
        assertEquals(Controller.Result.Locked,Controller.verify(context,"1234567890123"))
        val prefs=context.getSharedPreferences(BudgetApplication.PREFS_NAME,Context.MODE_PRIVATE)
        assertEquals(5,prefs.getInt("pin_attempt_failures",0))
        Controller.biometricVerified(context)
        assertEquals(0L,Controller.remaining(context))
        assertFalse(prefs.contains("pin_attempt_failures"))
        assertEquals(Controller.Result.Verified,Controller.verify(context,"1234567890123"))
    }
}
