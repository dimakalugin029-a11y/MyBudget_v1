package ru.mybudget.app

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import ru.mybudget.app.security.BackupCrypto
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/** V2 encrypts the whole ZIP, including photographs. Legacy JSON and ZIP readers remain supported. */
@OptIn(ExperimentalEncodingApi::class)
object FullBackupContainer {
    const val MAX_FILE_BYTES = 128 * 1024 * 1024
    const val MAX_ZIP_BYTES = 64 * 1024 * 1024
    fun encode(zip: ByteArray, password: String?): ByteArray {
        require(zip.size <= MAX_ZIP_BYTES) { "Резервная копия слишком большая" }
        if (password.isNullOrBlank()) return zip
        val envelope = JsonObject().apply {
            addProperty("fullArchiveVersion", 2)
            addProperty("archive", Base64.encode(zip))
        }
        return BackupCrypto.encrypt(envelope.toString(), password).toByteArray(Charsets.UTF_8).also {
            require(it.size <= MAX_FILE_BYTES) { "Резервная копия слишком большая" }
        }
    }

    /** Input is already decrypted. Null denotes a legacy plain JSON backup. */
    fun unpack(json: String): BackupArchiveHelper.ArchiveContent? {
        val root = JsonParser.parseString(json)
        if (!root.isJsonObject || !root.asJsonObject.has("fullArchiveVersion")) return null
        val envelope = root.asJsonObject
        require(envelope.get("fullArchiveVersion").asInt == 2) { "Неподдерживаемый формат полной копии" }
        val encoded = envelope.get("archive").asString
        require(encoded.length.toLong() <= (MAX_ZIP_BYTES.toLong() + 2) / 3 * 4) { "Архив слишком большой" }
        val zip = Base64.decode(encoded)
        require(zip.size <= MAX_ZIP_BYTES && BackupArchiveHelper.isZip(zip)) { "Повреждённая полная копия" }
        return BackupArchiveHelper.readArchive(zip).also { validateAttachments(it) }
    }

    fun validateAttachments(content: BackupArchiveHelper.ArchiveContent) {
        val root = JsonParser.parseString(content.json).asJsonObject
        val photos = root.getAsJsonArray("utilityBillPhotos") ?: return
        photos.forEach { photo ->
            val path = photo.asJsonObject.get("storedUri").asString
            require(path.startsWith(BackupArchiveHelper.PHOTOS_PREFIX) &&
                content.photoBytes[path]?.isNotEmpty() == true) { "В полной копии отсутствует фотография: $path" }
        }
    }
}
