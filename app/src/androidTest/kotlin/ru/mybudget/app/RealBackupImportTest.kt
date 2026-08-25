package ru.mybudget.app

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class RealBackupImportTest {
    private lateinit var backupJson: String

    @Before
    fun loadBackup() {
        backupJson = readBackupJson()
        assumeTrue(
            "Real backup not found. Copy MyBudget_backup_*.json to androidTest/assets/ or /sdcard/Download/",
            backupJson.isNotBlank(),
        )
    }

    @Test
    fun realBackup_importsSuccessfully() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val backupManager = BackupManager(context)
        val budgetManager = BudgetManager.getInstance(context)

        val result = backupManager.importFromJson(backupJson)
        assertTrue(result.message, result.success)

        budgetManager.reloadCategoriesFromDatabase()
        val categories = budgetManager.getCategoriesAsync(forceReload = true)
        assertTrue(categories.isNotEmpty())

        val profiles = budgetManager.getBudgetProfilesAsync()
        assertTrue(profiles.isNotEmpty())

        val bills = budgetManager.utilityDao.getAllBills()
        assertTrue(bills.isNotEmpty())
    }

    @Test
    fun realBackup_exportRoundTrip_preservesCategoryCount() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val backupManager = BackupManager(context)
        val budgetManager = BudgetManager.getInstance(context)

        assumeTrue(backupManager.importFromJson(backupJson).success)

        budgetManager.reloadCategoriesFromDatabase()
        val countBefore = budgetManager.getCategoriesAsync(forceReload = true).size
        val reexported = backupManager.exportToJson()
        assumeTrue(backupManager.importFromJson(reexported).success)

        budgetManager.reloadCategoriesFromDatabase()
        val countAfter = budgetManager.getCategoriesAsync(forceReload = true).size
        assertEquals(countBefore, countAfter)
    }

    private fun readBackupJson(): String {
        val context = InstrumentationRegistry.getInstrumentation().context
        runCatching {
            context.assets.open(REAL_BACKUP_ASSET).bufferedReader().use { return it.readText() }
        }
        val sdcard = File("/sdcard/Download/$REAL_BACKUP_ASSET")
        if (sdcard.isFile) return sdcard.readText()
        val alt = File("/sdcard/$REAL_BACKUP_ASSET")
        if (alt.isFile) return alt.readText()
        return ""
    }

    companion object {
        private const val REAL_BACKUP_ASSET = "real_backup_2026-08-18.json"
    }
}
