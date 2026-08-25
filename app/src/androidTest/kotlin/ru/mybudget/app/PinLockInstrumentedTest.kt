package ru.mybudget.app

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import ru.mybudget.app.setup.AppLockPreferences

@RunWith(AndroidJUnit4::class)
class PinLockInstrumentedTest {
    private lateinit var context: android.content.Context

    @Before
    fun resetLock() {
        context = ApplicationProvider.getApplicationContext()
        AppLockPreferences.setEnabled(context, false)
        AppLockPreferences.setPin(context, TEST_PIN)
    }

    @Test
    fun pin_verify_acceptsCorrectRejectsWrong() {
        assertTrue(AppLockPreferences.verifyPin(context, TEST_PIN))
        assertFalse(AppLockPreferences.verifyPin(context, "0000"))
    }

    @Test
    fun pinLock_enabledBlocksAfterTimeout() {
        AppLockPreferences.setEnabled(context, true)
        val staleUnlock = System.currentTimeMillis() - AppLockPreferences.LOCK_TIMEOUT_MS - 5_000L
        context.getSharedPreferences(BudgetApplication.PREFS_NAME, android.content.Context.MODE_PRIVATE)
            .edit()
            .putLong("app_lock_last_unlock_ms", staleUnlock)
            .apply()
        assertTrue(AppLockPreferences.shouldLock(context, System.currentTimeMillis() - 1_000L))
    }

    @Test
    fun pinLock_disabled_neverLocks() {
        AppLockPreferences.setEnabled(context, false)
        assertFalse(AppLockPreferences.shouldLock(context, System.currentTimeMillis() - 999_999L))
    }

    @Test
    fun encryptedBackup_withPinPassword_roundTrips() = runBlocking {
        val backupManager = BackupManager(context)
        val budgetManager = BudgetManager.getInstance(context)
        ScreenTestSeed.prepare(context)

        val beforeCount = budgetManager.getCategoriesAsync(forceReload = true).size
        val encrypted = backupManager.exportToJson(TEST_PIN)
        assertTrue(backupManager.importFromJson(encrypted, "wrong").success.not())

        val result = backupManager.importFromJson(encrypted, TEST_PIN)
        assertTrue(result.message, result.success)

        budgetManager.reloadCategoriesFromDatabase()
        assertTrue(budgetManager.getCategoriesAsync(forceReload = true).size >= beforeCount)
    }

    companion object {
        private const val TEST_PIN = "4321"
    }
}
