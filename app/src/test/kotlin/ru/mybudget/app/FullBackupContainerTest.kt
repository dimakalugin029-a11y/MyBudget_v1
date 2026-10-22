package ru.mybudget.app

import org.junit.Assert.*
import org.junit.Test
import ru.mybudget.app.security.BackupCrypto
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class FullBackupContainerTest {
    private fun zip(photo: Boolean = true): ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { stream ->
            stream.putNextEntry(ZipEntry("data.json"))
            stream.write("""{"version":24,"utilityBillPhotos":[{"storedUri":"photos/photo_0.jpg"}]}""".toByteArray())
            stream.closeEntry()
            if (photo) {
                stream.putNextEntry(ZipEntry("photos/photo_0.jpg"))
                stream.write(byteArrayOf(3, 1, 4, 1, 5)); stream.closeEntry()
            }
        }
    }.toByteArray()

    @Test fun protectedContainerEncryptsPhotosAndRoundTrips() {
        val bytes = FullBackupContainer.encode(zip(), "test-password")
        assertFalse(BackupArchiveHelper.isZip(bytes))
        val encrypted = bytes.toString(Charsets.UTF_8)
        assertTrue(BackupCrypto.isEncryptedJson(encrypted))
        assertFalse(encrypted.contains("photo_0.jpg"))
        val restored = FullBackupContainer.unpack(BackupCrypto.decrypt(encrypted, "test-password").getOrThrow())!!
        assertArrayEquals(byteArrayOf(3, 1, 4, 1, 5), restored.photoBytes["photos/photo_0.jpg"])
    }
    @Test fun wrongPasswordAndTamperingCannotDecrypt() {
        val encrypted = FullBackupContainer.encode(zip(), "correct").toString(Charsets.UTF_8)
        assertTrue(BackupCrypto.decrypt(encrypted, "incorrect").isFailure)
        val root = com.google.gson.JsonParser.parseString(encrypted).asJsonObject
        val payload = root.get("payload").asString
        root.addProperty("payload", (if (payload[0] == 'A') "B" else "A") + payload.drop(1))
        assertTrue(BackupCrypto.decrypt(root.toString(), "correct").isFailure)
    }
    @Test fun plainArchiveKeepsAllPhotoBytes() {
        val bytes = zip()
        assertArrayEquals(bytes, FullBackupContainer.encode(bytes, null))
        FullBackupContainer.validateAttachments(BackupArchiveHelper.readArchive(bytes))
    }
    @Test fun missingPhotoIsRejectedBeforeImport() {
        assertTrue(runCatching { FullBackupContainer.validateAttachments(BackupArchiveHelper.readArchive(zip(false))) }.isFailure)
    }
    @Test fun legacyJsonIsNotMistakenForFullContainer() {
        assertNull(FullBackupContainer.unpack("""{"version":24,"categories":[]}"""))
    }
    @Test fun unknownVersionIsRejected() {
        assertTrue(runCatching { FullBackupContainer.unpack("""{"fullArchiveVersion":99,"archive":""}""") }.isFailure)
    }
    @Test fun archiveWithoutDataIsRejected() {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { it.putNextEntry(ZipEntry("photos/x.jpg")); it.write(byteArrayOf(1)); it.closeEntry() }
        assertTrue(runCatching { BackupArchiveHelper.readArchive(out.toByteArray()) }.isFailure)
    }
}
