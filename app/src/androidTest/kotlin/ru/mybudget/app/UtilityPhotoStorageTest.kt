package ru.mybudget.app

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import ru.mybudget.app.data.UtilityBillEntity
import ru.mybudget.app.data.UtilityBillPhotoEntity
import ru.mybudget.app.utilities.UtilityPhotoPreferences
import ru.mybudget.app.utilities.UtilityPhotoStorage
import java.io.File

@RunWith(AndroidJUnit4::class)
class UtilityPhotoStorageTest {
    private fun context(): Context {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val prefix = "photo-storage-test-${java.util.UUID.randomUUID()}-"
        return object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int) = base.getSharedPreferences(prefix + name, mode)
        }
    }
    private val bill = UtilityBillEntity(id = 1, year = 2026, month = 10, apartmentArea = 40.0)

    @Test fun pickedPhotoSurvivesDeletedOriginalAndCanBeExported() {
        val context = context()
        val source = File.createTempFile("picked-photo-", ".jpg", context.cacheDir)
        val bytes = byteArrayOf(10, 20, 30)
        var stored: Uri? = null
        try {
            source.writeBytes(bytes)
            val saved = UtilityPhotoStorage.persistPhoto(context, Uri.fromFile(source), bill, "receipt", 0)
            assertNotNull(saved)
            stored = Uri.parse(saved!!)
            assertEquals("${context.packageName}.restoredphotos", stored.authority)
            assertTrue(source.delete())
            assertArrayEquals(bytes, context.contentResolver.openInputStream(stored)?.use { it.readBytes() })
            val archive = BackupArchiveHelper.exportArchive(context, "{}", listOf(UtilityBillPhotoEntity(billId = 1, photoType = "receipt", storedUri = saved)))
            assertArrayEquals(bytes, BackupArchiveHelper.readArchive(archive).photoBytes[BackupArchiveHelper.photoEntryName(0)])
        } finally {
            source.delete()
            stored?.let { context.contentResolver.delete(it, null, null) }
        }
    }

    @Test fun missingAttachmentStopsFullExportWithActionableLocation() {
        val context = context()
        val missing = File(context.cacheDir, "missing-${java.util.UUID.randomUUID()}.jpg")
        val photo = UtilityBillPhotoEntity(billId = 1, photoType = "receipt", storedUri = Uri.fromFile(missing).toString())
        try {
            BackupArchiveHelper.exportArchive(context, "{}", listOf(photo), mapOf(1 to "Дом, 2026-10"))
            fail("An incomplete archive must not be returned")
        } catch (e: IllegalStateException) {
            assertTrue(e.message.orEmpty().contains("Дом, 2026-10"))
            assertTrue(e.message.orEmpty().contains("Полная копия не создана"))
        }
    }

    @Test fun unreadableSourceIsRejectedInsteadOfSavingTemporaryUri() {
        val context = context()
        val missing = File(context.cacheDir, "missing-${java.util.UUID.randomUUID()}.jpg")
        assertNull(UtilityPhotoStorage.persistPhoto(context, Uri.fromFile(missing), bill, "receipt", 0))
    }

    @Test fun inaccessibleOptionalFolderDoesNotBreakNewOrImportedPhotos() {
        val context = context()
        UtilityPhotoPreferences.setFolderUri(context, Uri.parse("content://missing.photo.provider/tree/test"))
        val source = File.createTempFile("picked-photo-", ".jpg", context.cacheDir)
        val stored = mutableListOf<String>()
        val bytes = byteArrayOf(1, 2, 3)
        try {
            source.writeBytes(bytes)
            stored += requireNotNull(UtilityPhotoStorage.persistPhoto(context, Uri.fromFile(source), bill, "meter", 0))
            stored += requireNotNull(UtilityPhotoStorage.persistImportedBytes(context, bill, "receipt", 0, bytes))
            assertTrue(source.delete())
            stored.forEach { uri ->
                assertEquals("${context.packageName}.restoredphotos", Uri.parse(uri).authority)
                assertArrayEquals(bytes, context.contentResolver.openInputStream(Uri.parse(uri))?.use { it.readBytes() })
            }
        } finally {
            source.delete()
            stored.forEach { UtilityPhotoStorage.deleteStoredPhoto(context, it) }
        }
    }

    @Test fun readableLegacyAttachmentGetsIndependentPrivateCopy() {
        val context = context()
        val source = File.createTempFile("legacy-photo-", ".jpg", context.cacheDir)
        val bytes = byteArrayOf(3, 4, 5)
        var stored: String? = null
        try {
            source.writeBytes(bytes)
            val saved = requireNotNull(UtilityPhotoStorage.protectExistingPhoto(context, Uri.fromFile(source).toString()))
            stored = saved
            assertTrue(source.exists())
            assertTrue(source.delete())
            assertArrayEquals(bytes, context.contentResolver.openInputStream(Uri.parse(saved))?.use { it.readBytes() })
            assertNull(UtilityPhotoStorage.protectExistingPhoto(context, saved))
        } finally {
            source.delete()
            stored?.let { UtilityPhotoStorage.deleteStoredPhoto(context, it) }
        }
    }
}
