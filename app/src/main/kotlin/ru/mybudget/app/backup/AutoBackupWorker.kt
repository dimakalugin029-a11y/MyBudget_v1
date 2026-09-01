package ru.mybudget.app.backup

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.documentfile.provider.DocumentFile
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import ru.mybudget.app.AppNotificationsHelper
import ru.mybudget.app.BackupManager
import ru.mybudget.app.BudgetApplication
import ru.mybudget.app.R
import ru.mybudget.app.security.AutoBackupSecrets
import ru.mybudget.app.setup.AutoBackupPreferences
import ru.mybudget.app.setup.WebDavBackupPreferences
import ru.mybudget.app.security.WebDavSecrets
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class AutoBackupWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        if (!AutoBackupPreferences.canRun(applicationContext)) {
            return Result.success()
        }
        val treeUri = AutoBackupPreferences.folderUri(applicationContext)
            ?: return fail(applicationContext.getString(R.string.auto_backup_folder_not_selected), retry = false)
        val tree = DocumentFile.fromTreeUri(applicationContext, treeUri)
            ?: return fail(applicationContext.getString(R.string.auto_backup_folder_unavailable), retry = false)
        if (!tree.canWrite()) {
            return fail(applicationContext.getString(R.string.auto_backup_folder_unavailable), retry = false)
        }
        val encrypt = AutoBackupPreferences.isEncryptEnabled(applicationContext)
        val password = if (encrypt) AutoBackupSecrets.getPassword(applicationContext) else null
        if (encrypt && password.isNullOrBlank()) {
            return fail(applicationContext.getString(R.string.auto_backup_encrypt_unavailable), retry = false)
        }
        pruneOldBackups(tree)
        val stamp = LocalDateTime.now().format(fileStamp)
        val suffix = if (encrypt) "_encrypted" else ""
        val displayName = "MyBudget_auto_${stamp}$suffix.json"
        val file = tree.createFile("application/json", displayName)
            ?: return fail(applicationContext.getString(R.string.auto_backup_write_failed))
        val ok = BackupManager(applicationContext).exportToFile(file.uri, password)
        if (!ok) {
            file.delete()
            return fail(applicationContext.getString(R.string.auto_backup_write_failed))
        }
        uploadToWebDavIfNeeded(displayName, file.uri)
        val now = System.currentTimeMillis()
        AutoBackupPreferences.setLastAutoExportMs(applicationContext, now)
        applicationContext.getSharedPreferences(BudgetApplication.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(AppNotificationsHelper.KEY_LAST_EXPORT_PROMPT_MS, now)
            .apply()
        val message = applicationContext.getString(R.string.auto_backup_success_toast, displayName)
        notifyUser(message)
        return Result.success()
    }

    private fun pruneOldBackups(tree: DocumentFile) {
        val existing = tree.listFiles()
            .filter { file ->
                val name = file.name.orEmpty()
                name.startsWith("MyBudget_auto_") && name.endsWith(".json")
            }
            .sortedBy { it.lastModified() }
        val overflow = existing.size - 2
        if (overflow > 0) {
            existing.take(overflow).forEach { it.delete() }
        }
    }

    private suspend fun uploadToWebDavIfNeeded(displayName: String, localUri: Uri) {
        if (!WebDavBackupPreferences.canUpload(applicationContext)) return
        val bytes = applicationContext.contentResolver.openInputStream(localUri)?.use { it.readBytes() } ?: return
        val password = WebDavSecrets.getPassword(applicationContext) ?: return
        WebDavBackupClient.uploadFile(
            baseUrl = WebDavBackupPreferences.baseUrl(applicationContext),
            username = WebDavBackupPreferences.username(applicationContext),
            password = password,
            remotePath = WebDavBackupPreferences.remotePath(applicationContext),
            fileName = displayName,
            body = bytes,
        )
    }

    private fun fail(message: String, retry: Boolean = true): Result {
        notifyUser(applicationContext.getString(R.string.auto_backup_failed_toast, message))
        return if (retry) Result.retry() else Result.failure()
    }

    private fun notifyUser(message: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
        }
        runCatching {
            ensureChannel()
            val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(applicationContext.getString(R.string.auto_backup_title))
                .setContentText(message)
                .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                .setAutoCancel(true)
                .build()
            NotificationManagerCompat.from(applicationContext).notify(NOTIFICATION_ID, notification)
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = applicationContext.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            applicationContext.getString(R.string.auto_backup_title),
            NotificationManager.IMPORTANCE_DEFAULT,
        )
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "auto_backup"
        private const val NOTIFICATION_ID = 4101
        private val fileStamp: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss")

        fun folderDisplayName(context: Context, uri: Uri): String {
            val name = DocumentFile.fromTreeUri(context, uri)?.name
            return name?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment ?: uri.toString()
        }
    }
}
