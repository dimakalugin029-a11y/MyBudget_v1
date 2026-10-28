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
    private const val MAX_ENTRY_BYTES = 32 * 1024 * 1024
    private const val MAX_ARCHIVE_BYTES = 128 * 1024 * 1024
    internal fun readBounded(stream: java.io.InputStream, limit: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val size = stream.read(buffer)
            if (size < 0) break
            require(output.size().toLong() + size <= limit) { "Архив слишком большой" }
            output.write(buffer, 0, size)
        }
        return output.toByteArray()
    }
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
        billLabels: Map<Int, String> = emptyMap(),
    ): ByteArray {
        val output = ByteArrayOutputStream()
        require(plainJson.toByteArray(Charsets.UTF_8).size <= MAX_ENTRY_BYTES) { "Данные копии слишком большие" }
        var totalBytes = plainJson.toByteArray(Charsets.UTF_8).size.toLong()
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry(DATA_ENTRY))
            zip.write(plainJson.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            photos.forEachIndexed { index, photo ->
                val bytes = try {
                    val contents = readPhotoBytes(context, photo.storedUri) ?: throw java.io.FileNotFoundException()
                    if (contents.isEmpty()) throw java.io.EOFException()
                    contents
                } catch (e: Exception) {
                    val reason = when (e) {
                        is SecurityException -> "Нет доступа к файлу."
                        is java.io.FileNotFoundException -> "Файл не найден или недоступен."
                        is java.io.EOFException -> "Файл пуст."
                        is IllegalArgumentException -> "Файл больше 32 МБ или ссылка некорректна."
                        else -> "Ошибка чтения файла."
                    }
                    throw IllegalStateException(unreadablePhotoMessage(photo, index, billLabels[photo.billId], reason), e)
                }
                totalBytes += bytes.size
                require(totalBytes <= MAX_ARCHIVE_BYTES) { "Архив слишком большой" }
                zip.putNextEntry(ZipEntry(photoEntryName(index)))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }

    internal fun unreadablePhotoMessage(photo: UtilityBillPhotoEntity, index: Int, billLabel: String?, reason: String = ""): String {
        val type = if (photo.photoType == UtilityBillPhotoEntity.TYPE_METER) "счётчик" else "квитанция"
        val location = billLabel ?: "платёж #${photo.billId}"
        return "Недоступно фото №${index + 1} в копии: $location, $type №${photo.sortOrder + 1}. " +
            "$reason Откройте Коммуналка → этот объект и месяц → Фото. " +
            "Повторно выберите папку фото в настройках или добавьте фото заново вместо недоступного. Полная копия не создана."
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
        var total = 0L
        ZipInputStream(ByteArrayInputStream(input)).use { zip ->
            val names = mutableSetOf<String>()
            var entry = zip.nextEntry
            while (entry != null) {
                require(names.add(entry.name)) { "Повторяющийся файл в архиве" }
                val bytes = readBounded(zip, MAX_ENTRY_BYTES)
                total += bytes.size
                require(total <= MAX_ARCHIVE_BYTES) { "Архив слишком большой" }
                when {
                    entry.name == DATA_ENTRY -> json = bytes.toString(Charsets.UTF_8)
                    entry.name.startsWith(PHOTOS_PREFIX) -> photoBytes[entry.name] = bytes
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        require(!json.isNullOrBlank()) { "В архиве отсутствуют данные" }
        return ArchiveContent(
            json = json.orEmpty(),
            photoBytes = photoBytes,
        )
    }

    fun readArchive(context: Context, uri: Uri): ArchiveContent? {
        val bytes = runCatching { context.contentResolver.openInputStream(uri)?.use { readBounded(it, MAX_ARCHIVE_BYTES) } }.getOrNull() ?: return null
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
        return context.contentResolver.openInputStream(uri)?.use { readBounded(it, MAX_ENTRY_BYTES) }
    }
}
