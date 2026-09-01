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
        val folderUri = UtilityPhotoPreferences.folderUri(appContext)
        if (folderUri != null) {
            copyToMonthFolder(appContext, sourceUri, folderUri, bill, photoType, sortOrder)?.let { return it }
        }
        runCatching {
            appContext.contentResolver.takePersistableUriPermission(
                sourceUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        return sourceUri.toString()
    }

    fun deleteStoredPhoto(context: Context, storedUri: String) {
        val uri = runCatching { Uri.parse(storedUri) }.getOrNull() ?: return
        val folderUri = UtilityPhotoPreferences.folderUri(context.applicationContext) ?: return
        val tree = DocumentFile.fromTreeUri(context.applicationContext, folderUri) ?: return
        if (!isUnderTree(tree, uri)) return
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
        val folderUri = UtilityPhotoPreferences.folderUri(appContext) ?: return null
        return writeBytesToMonthFolder(appContext, folderUri, bill, photoType, sortOrder, bytes)
    }

    private fun writeBytesToMonthFolder(
        context: Context,
        folderUri: Uri,
        bill: UtilityBillEntity,
        photoType: String,
        sortOrder: Int,
        bytes: ByteArray,
    ): String? {
        val tree = DocumentFile.fromTreeUri(context, folderUri) ?: return null
        if (!tree.canWrite()) return null
        val monthDir = findOrCreateDir(tree, ROOT_DIR)
            ?.let { findOrCreateDir(it, monthFolderLabel(bill.year, bill.month)) }
            ?.let { findOrCreateDir(it, typeDirName(photoType)) }
            ?: return null
        val prefix = if (photoType == UtilityBillPhotoEntity.TYPE_METER) "meter" else "receipt"
        val fileName = "${prefix}_${sortOrder + 1}_import_${System.currentTimeMillis()}.jpg"
        val dest = monthDir.createFile("image/jpeg", fileName) ?: return null
        val copied = runCatching {
            context.contentResolver.openOutputStream(dest.uri)?.use { output ->
                output.write(bytes)
            } ?: error("no output")
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
