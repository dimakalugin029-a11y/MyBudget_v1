package ru.mybudget.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mybudget.app.data.UtilityBillPhotoEntity
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BackupArchiveHelperTest {
    @Test
    fun unreadablePhotoIdentifiesPropertyMonthAndNumberWithoutExposingUri() {
        val photo = UtilityBillPhotoEntity(id = 7, billId = 3, photoType = UtilityBillPhotoEntity.TYPE_METER,
            storedUri = "content://private-document", sortOrder = 2)
        val message = BackupArchiveHelper.unreadablePhotoMessage(photo, 0, "Квартира, 2026-10")
        assertTrue(message.contains("Квартира, 2026-10"))
        assertTrue(message.contains("счётчик №3"))
        assertTrue(message.contains("Полная копия не создана"))
        org.junit.Assert.assertFalse(message.contains(photo.storedUri))
    }

    @Test
    fun readArchive_readsEmbeddedJson() {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry(BackupArchiveHelper.DATA_ENTRY))
            zip.write("""{"version":17}""".toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry(BackupArchiveHelper.photoEntryName(0)))
            zip.write(byteArrayOf(1, 2, 3))
            zip.closeEntry()
        }

        val content = BackupArchiveHelper.readArchive(output.toByteArray())

        assertTrue(content.json.contains("17"))
        assertEquals(3, content.photoBytes[BackupArchiveHelper.photoEntryName(0)]?.size)
    }

    @Test
    fun rewritePhotosForArchive_usesArchivePaths() {
        val photos = listOf(
            UtilityBillPhotoEntity(
                billId = 3,
                photoType = "receipt",
                storedUri = "content://x",
                sortOrder = 0,
            ),
        )

        val rewritten = BackupArchiveHelper.rewritePhotosForArchive(photos)

        assertEquals(BackupArchiveHelper.photoEntryName(0), rewritten.first().storedUri)
    }
}
