package ru.mybudget.app.utilities

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import ru.mybudget.app.data.UtilityBillEntity
import ru.mybudget.app.data.UtilityBillPhotoEntity

object UtilityPhotoStorage {
    private const val ROOT_DIR = "ЖКХ"
    private const val RECEIPT_DIR = "квитанции"
    private const val METER_DIR = "счетчики"

    fun persistPhoto(
        context: Context,
        sourceUri: Uri,
        bill: UtilityBillEntity,
        photoType: String,
        sortOrder: Int,
    ): String? {
        val appContext = context.applicationContext
        // Gallery grants and SAF folders are not reliable as the only copy of an attachment.
        val stored = copyToPrivateStorage(appContext, sourceUri) ?: return null
        mirrorToMonthFolder(appContext, Uri.parse(stored), bill, photoType, sortOrder)
        return stored
    }

    /** Protect readable legacy attachments without removing or changing the original file. */
    fun protectExistingPhoto(context: Context, storedUri: String): String? {
        val uri = runCatching { Uri.parse(storedUri) }.getOrNull() ?: return null
        if (uri.authority == "${context.packageName}.restoredphotos") return null
        return copyToPrivateStorage(context.applicationContext, uri)
    }

    private fun copyToPrivateStorage(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.openInputStream(uri)?.use { writePrivatePhoto(context, it) }
    }.getOrNull()

    private fun writePrivatePhoto(context: Context, input: java.io.InputStream): String {
        val directory = java.io.File(context.filesDir, "restored_photos")
        check(directory.isDirectory || directory.mkdirs()) { "Не удалось создать папку фотографий" }
        val file = java.io.File(directory, "${java.util.UUID.randomUUID()}.jpg")
        try {
            file.outputStream().use { output ->
                val buffer = ByteArray(8192)
                var size = 0L
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    size += count
                    require(size <= 32L * 1024 * 1024) { "Фотография больше 32 МБ" }
                    output.write(buffer, 0, count)
                }
                require(size > 0) { "Фотография пуста" }
            }
            return androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.restoredphotos", file).toString()
        } catch (e: Exception) {
            file.delete()
            throw e
        }
    }

    private fun mirrorToMonthFolder(context: Context, uri: Uri, bill: UtilityBillEntity, photoType: String, sortOrder: Int) {
        // A revoked or unavailable optional folder must not prevent saving the private copy.
        runCatching {
            UtilityPhotoPreferences.folderUri(context)?.let { folder ->
                copyToMonthFolder(context, uri, folder, bill, photoType, sortOrder)
            }
        }
    }

    fun deleteStoredPhoto(context: Context, storedUri: String) {
        val uri = runCatching { Uri.parse(storedUri) }.getOrNull() ?: return
        if (uri.authority == "${context.packageName}.restoredphotos") {
            runCatching { context.contentResolver.delete(uri, null, null) }
            return
        }
        val folderUri = UtilityPhotoPreferences.folderUri(context.applicationContext) ?: return
        val tree = DocumentFile.fromTreeUri(context.applicationContext, folderUri) ?: return
        if (!runCatching { isUnderTree(tree, uri) }.getOrDefault(false)) return
        DocumentFile.fromSingleUri(context.applicationContext, uri)?.delete()
    }

    fun monthFolderLabel(year: Int, month: Int): String =
        "%04d-%02d".format(year, month)

    fun typeDirName(photoType: String): String =
        if (photoType == UtilityBillPhotoEntity.TYPE_METER) METER_DIR else RECEIPT_DIR

    fun persistImportedBytes(
        context: Context,
        bill: UtilityBillEntity,
        photoType: String,
        sortOrder: Int,
        bytes: ByteArray,
    ): String? {
        if (bytes.isEmpty()) return null
        val appContext = context.applicationContext
        val stored = runCatching { bytes.inputStream().use { writePrivatePhoto(appContext, it) } }.getOrNull() ?: return null
        mirrorToMonthFolder(appContext, Uri.parse(stored), bill, photoType, sortOrder)
        return stored
    }

    private fun copyToMonthFolder(
        context: Context,
        sourceUri: Uri,
        folderUri: Uri,
        bill: UtilityBillEntity,
        photoType: String,
        sortOrder: Int,
    ): String? {
        val tree = DocumentFile.fromTreeUri(context, folderUri) ?: return null
        if (!tree.canWrite()) return null
        val monthDir = findOrCreateDir(tree, ROOT_DIR)
            ?.let { findOrCreateDir(it, monthFolderLabel(bill.year, bill.month)) }
            ?.let { findOrCreateDir(it, typeDirName(photoType)) }
            ?: return null
        val prefix = if (photoType == UtilityBillPhotoEntity.TYPE_METER) "meter" else "receipt"
        val fileName = "${prefix}_${sortOrder + 1}_${System.currentTimeMillis()}.jpg"
        val dest = monthDir.createFile("image/jpeg", fileName) ?: return null
        val copied = runCatching {
            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                context.contentResolver.openOutputStream(dest.uri)?.use { output ->
                    input.copyTo(output)
                } ?: error("no output")
            } ?: error("no input")
        }.isSuccess
        if (!copied) {
            dest.delete()
            return null
        }
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                dest.uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        return dest.uri.toString()
    }

    private fun findOrCreateDir(parent: DocumentFile, name: String): DocumentFile? {
        parent.findFile(name)?.takeIf { it.isDirectory }?.let { return it }
        return parent.createDirectory(name)
    }

    private fun isUnderTree(tree: DocumentFile, uri: Uri): Boolean {
        val treeUri = tree.uri
        val docId = android.provider.DocumentsContract.getTreeDocumentId(treeUri)
        val targetDocId = android.provider.DocumentsContract.getDocumentId(uri)
        return targetDocId.startsWith("$docId/")
    }
}
