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
