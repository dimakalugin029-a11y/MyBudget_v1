package ru.mybudget.app

import android.content.Context
import android.net.Uri
import ru.mybudget.app.data.UtilityBillPhotoEntity
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

object BackupArchiveHelper {
    const val DATA_ENTRY = "data.json"
    const val PHOTOS_PREFIX = "photos/"

    data class ArchiveContent(
        val json: String,
        val photoBytes: Map<String, ByteArray>,
    )

    fun photoEntryName(index: Int): String = "${PHOTOS_PREFIX}photo_$index.jpg"

    fun exportArchive(
        context: Context,
        plainJson: String,
        photos: List<UtilityBillPhotoEntity>,
    ): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry(DATA_ENTRY))
            zip.write(plainJson.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            photos.forEachIndexed { index, photo ->
                val bytes = readPhotoBytes(context, photo.storedUri) ?: return@forEachIndexed
                zip.putNextEntry(ZipEntry(photoEntryName(index)))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }

    fun rewritePhotosForArchive(
        photos: List<UtilityBillPhotoEntity>,
    ): List<UtilityBillPhotoEntity> {
        return photos.mapIndexed { index, photo ->
            photo.copy(storedUri = photoEntryName(index))
        }
    }

    fun readArchive(input: ByteArray): ArchiveContent {
        val photoBytes = linkedMapOf<String, ByteArray>()
        var json: String? = null
        ZipInputStream(ByteArrayInputStream(input)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val bytes = zip.readBytes()
                when {
                    entry.name == DATA_ENTRY -> json = bytes.toString(Charsets.UTF_8)
                    entry.name.startsWith(PHOTOS_PREFIX) -> photoBytes[entry.name] = bytes
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        return ArchiveContent(
            json = json.orEmpty(),
            photoBytes = photoBytes,
        )
    }

    fun readArchive(context: Context, uri: Uri): ArchiveContent? {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
        if (!isZip(bytes)) return null
        return runCatching { readArchive(bytes) }.getOrNull()
    }

    fun isZip(bytes: ByteArray): Boolean {
        return bytes.size >= 4 &&
            bytes[0] == 'P'.code.toByte() &&
            bytes[1] == 'K'.code.toByte()
    }

    private fun readPhotoBytes(context: Context, storedUri: String): ByteArray? {
        if (storedUri.isBlank() || storedUri.startsWith(PHOTOS_PREFIX)) return null
        val uri = runCatching { Uri.parse(storedUri) }.getOrNull() ?: return null
        return runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull()
    }
}
