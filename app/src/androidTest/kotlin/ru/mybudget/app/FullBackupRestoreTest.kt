package ru.mybudget.app

import android.content.Context
import android.content.ContextWrapper
import androidx.core.content.FileProvider
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import ru.mybudget.app.data.*
import java.io.File

@RunWith(AndroidJUnit4::class)
class FullBackupRestoreTest {
    @Test fun encryptedCopyRestoresMoneyPaymentLinksAndPhotoIntoEmptyDatabase() = runBlocking {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val prefix = "full-backup-test-${java.util.UUID.randomUUID()}-"
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int) = base.getSharedPreferences(prefix + name, mode)
        }
        val source = Room.inMemoryDatabaseBuilder(context, BudgetDatabase::class.java).addCallback(ru.mybudget.app.data.BalanceLedgerSchema.callback).build()
        val target = Room.inMemoryDatabaseBuilder(context, BudgetDatabase::class.java).addCallback(ru.mybudget.app.data.BalanceLedgerSchema.callback).build()
        val folder = File(base.filesDir, "restored_photos").apply { mkdirs() }
        val original = File(folder, "test-${java.util.UUID.randomUUID()}.jpg")
        val bytes = byteArrayOf(10, 20, 30, 40)
        var restoredUri: android.net.Uri? = null
        try {
            source.budgetDao().insertBudgetProfile(BudgetProfileEntity(id = 1, name = "Test"))
            source.budgetDao().insertCategory(BudgetCategoryEntity(1, "Leaf", 0, currentBalance = 1000.0))
            val repository = AutoRepository(source)
            val vehicle = repository.insertVehicle("Test car")
            repository.saveEntry(ru.mybudget.app.auto.AutoEntry(ru.mybudget.app.auto.AutoEntryType.REPAIR,
                vehicleId = vehicle, dateEpochDay = 20000, amount = 100.0, categoryId = 1,
                paymentMethod = ru.mybudget.app.auto.AutoPaymentMethod.BUDGET), "test-save")
            val property = source.utilityDao().insertProperty(UtilityPropertyEntity(name = "Test home")).toInt()
            val bill = source.utilityDao().insertBill(UtilityBillEntity(propertyId = property, year = 2026, month = 10, apartmentArea = 40.0)).toInt()
            original.writeBytes(bytes)
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.restoredphotos", original)
            source.utilityDao().insertBillPhoto(UtilityBillPhotoEntity(billId = bill, photoType = "receipt", storedUri = uri.toString()))
            val full = BackupManager(context, source).buildFullBackup("secret")
            assertTrue(original.delete()) // The old URI is no longer usable.
            val importer = BackupManager(context, target)
            assertFalse(importer.importFromJson(full.toString(Charsets.UTF_8), "wrong").success)
            assertTrue(target.budgetDao().getAllCategoriesForExport().isEmpty())
            assertTrue(importer.importFromJson(full.toString(Charsets.UTF_8), "secret").success)
            assertEquals(900.0, target.budgetDao().getAllCategoriesForExport().single().currentBalance, 0.0)
            assertEquals(1, target.budgetDao().getAllTransactionsOnce().size)
            val entry = AutoRepository(target).getEntries().single()
            assertNotNull(target.budgetDao().getTransactionById(entry.paidTransactionId!!.toInt()))
            val photo = target.utilityDao().getAllBillPhotosForExport().single()
            restoredUri = android.net.Uri.parse(photo.storedUri)
            assertEquals("${context.packageName}.restoredphotos", restoredUri!!.authority)
            assertArrayEquals(bytes, context.contentResolver.openInputStream(restoredUri!!)?.use { it.readBytes() })
        } finally {
            restoredUri?.let { context.contentResolver.delete(it, null, null) }
            original.delete()
            source.close(); target.close()
            // All preference writes above use an isolated test-only prefix.
        }
    }
}
