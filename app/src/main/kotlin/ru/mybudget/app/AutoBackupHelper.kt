package ru.mybudget.app

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.mybudget.app.security.AutoBackupSecrets
import ru.mybudget.app.setup.AutoBackupPreferences
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

object AutoBackupHelper {
    private val fileStamp: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss")

    suspend fun maybeRunAutoExport(context: Context): AutoExportResult? = withContext(Dispatchers.IO) {
        if (!AutoBackupPreferences.canRun(context)) return@withContext null
        val intervalMs = AutoBackupPreferences.intervalDays(context) * 24L * 60L * 60L * 1000L
        val last = AutoBackupPreferences.lastAutoExportMs(context)
        val now = System.currentTimeMillis()
        if (last > 0 && now - last < intervalMs) return@withContext null
        runExport(context, now)
    }

    suspend fun runExport(context: Context, now: Long = System.currentTimeMillis()): AutoExportResult =
        withContext(Dispatchers.IO) {
            val appContext = context.applicationContext
            val treeUri = AutoBackupPreferences.folderUri(appContext)
                ?: return@withContext AutoExportResult(
                    success = false,
                    message = appContext.getString(R.string.auto_backup_folder_not_selected),
                )
            val tree = DocumentFile.fromTreeUri(appContext, treeUri)
                ?: return@withContext AutoExportResult(
                    success = false,
                    message = appContext.getString(R.string.auto_backup_folder_unavailable),
                )
            if (!tree.canWrite()) {
                return@withContext AutoExportResult(
                    success = false,
                    message = appContext.getString(R.string.auto_backup_folder_unavailable),
                )
            }
            val encrypt = AutoBackupPreferences.isEncryptEnabled(appContext)
            val password = if (encrypt) AutoBackupSecrets.getPassword(appContext) else null
            if (encrypt && password.isNullOrBlank()) {
                return@withContext AutoExportResult(
                    success = false,
                    message = appContext.getString(R.string.auto_backup_encrypt_unavailable),
                )
            }
            val stamp = LocalDateTime.now().format(fileStamp)
            val suffix = if (encrypt) "_encrypted" else ""
            val displayName = "MyBudget_auto_${stamp}$suffix.json"
            val file = tree.createFile("application/json", displayName)
                ?: return@withContext AutoExportResult(
                    success = false,
                    message = appContext.getString(R.string.auto_backup_write_failed),
                )
            val ok = runCatching {
                val payload = BackupManager(appContext).exportToJson(password).toByteArray(Charsets.UTF_8)
                appContext.contentResolver.openOutputStream(file.uri)?.use { it.write(payload) } ?: error("cannot write backup")
                val digest = java.security.MessageDigest.getInstance("SHA-256")
                appContext.contentResolver.openInputStream(file.uri)?.use { input ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        digest.update(buffer, 0, count)
                    }
                } ?: error("cannot verify backup")
                java.security.MessageDigest.isEqual(digest.digest(), java.security.MessageDigest.getInstance("SHA-256").digest(payload))
            }.getOrDefault(false)
            if (!ok) {
                file.delete()
                return@withContext AutoExportResult(
                    success = false,
                    message = appContext.getString(R.string.auto_backup_write_failed),
                )
            }
            pruneOldBackups(tree, file.uri)
            AutoBackupPreferences.setLastAutoExportMs(appContext, now)
            appContext.getSharedPreferences(BudgetApplication.PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putLong(AppNotificationsHelper.KEY_LAST_EXPORT_PROMPT_MS, now)
                .apply()
            AutoExportResult(success = true, filename = displayName)
        }

    private fun pruneOldBackups(tree: DocumentFile, protectedUri: android.net.Uri) {
        val existing = tree.listFiles()
            .filter { file ->
                val name = file.name.orEmpty()
                name.startsWith("MyBudget_auto_") && name.endsWith(".json")
            }
            .sortedBy { it.lastModified() }
        val overflow = existing.size - 3
        if (overflow > 0) {
            existing.filter { it.uri != protectedUri }.take(overflow).forEach { it.delete() }
        }
    }
}
