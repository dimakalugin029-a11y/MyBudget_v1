package ru.mybudget.app

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackupImportExportTest {
    companion object {
        private lateinit var seed: ScreenTestSeed.SeedData

        @BeforeClass
        @JvmStatic
        fun prepareApp() {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            seed = ScreenTestSeed.prepare(context)
        }
    }

    @Test
    fun plainJsonExportImport_roundTrip_restoresBalance() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val budgetManager = BudgetManager.getInstance(context)
        val backupManager = BackupManager(context)

        val balanceBefore = budgetManager.getTotalBalance()
        val categoryCountBefore = budgetManager.getCategoriesAsync(forceReload = true).size
        val json = backupManager.exportToJson()

        budgetManager.recordTransaction(
            categoryId = seed.categoryId,
            amount = 250.0,
            type = "expense",
            description = "backup-test-mutation",
        )
        assertNotEquals(balanceBefore, budgetManager.getTotalBalance())

        val result = backupManager.importFromJson(json)
        assertTrue(result.message, result.success)

        budgetManager.reloadCategoriesFromDatabase()
        assertEquals(categoryCountBefore, budgetManager.getCategoriesAsync(forceReload = true).size)
        assertEquals(balanceBefore, budgetManager.getTotalBalance(), 0.01)
    }

    @Test
    fun importFromJson_rejectsXlsxAndTinyFiles() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val backupManager = BackupManager(context)

        val xlsx = backupManager.importFromJson("PK\u0003\u0004not-a-real-xlsx")
        assertTrue(!xlsx.success)
        assertTrue(xlsx.message.orEmpty().contains("Excel"))

        val tiny = backupManager.importFromJson("{\"version\":8}")
        assertTrue(!tiny.success)
        assertTrue(tiny.message.orEmpty().contains("маленьк"))
    }

    @Test
    fun encryptedExportImport_roundTrip_restoresData() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val budgetManager = BudgetManager.getInstance(context)
        val backupManager = BackupManager(context)
        val password = "test-backup-secret"

        val profilesBefore = budgetManager.getBudgetProfilesAsync().size
        val encrypted = backupManager.exportToJson(password)

        budgetManager.recordTransaction(
            categoryId = seed.categoryId,
            amount = 50.0,
            type = "income",
            description = "encrypted-backup-test",
        )

        val wrong = backupManager.importFromJson(encrypted, "wrong-password")
        assertTrue(!wrong.success)

        val result = backupManager.importFromJson(encrypted, password)
        assertTrue(result.message, result.success)

        budgetManager.reloadCategoriesFromDatabase()
        assertEquals(profilesBefore, budgetManager.getBudgetProfilesAsync().size)
    }
}
