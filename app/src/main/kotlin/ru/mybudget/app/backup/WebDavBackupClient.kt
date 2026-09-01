package ru.mybudget.app.backup

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64

object WebDavBackupClient {
    suspend fun uploadFile(
        baseUrl: String,
        username: String,
        password: String,
        remotePath: String,
        fileName: String,
        body: ByteArray,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val folderUrl = buildFolderUrl(baseUrl, remotePath)
            ensureFolder(folderUrl, username, password)
            val target = "$folderUrl/${fileName.trim('/')}"
            put(target, username, password, body)
        }
    }

    suspend fun testConnection(
        baseUrl: String,
        username: String,
        password: String,
        remotePath: String,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val folderUrl = buildFolderUrl(baseUrl, remotePath)
            ensureFolder(folderUrl, username, password)
            options(folderUrl, username, password)
        }
    }

    private fun buildFolderUrl(baseUrl: String, remotePath: String): String {
        val normalizedBase = baseUrl.trim().trimEnd('/')
        val path = remotePath.trim().trim('/')
        return if (path.isBlank()) normalizedBase else "$normalizedBase/$path"
    }

    private fun ensureFolder(folderUrl: String, username: String, password: String) {
        val code = mkcol(folderUrl, username, password)
        if (code !in 200..299 && code != 405) {
            options(folderUrl, username, password)
        }
    }

    private fun options(url: String, username: String, password: String) {
        val connection = openConnection(url, username, password, "OPTIONS")
        connection.connect()
        val code = connection.responseCode
        connection.disconnect()
        if (code !in 200..399) error("WebDAV unavailable: HTTP $code")
    }

    private fun mkcol(url: String, username: String, password: String): Int {
        val connection = openConnection(url, username, password, "MKCOL")
        connection.connect()
        val code = connection.responseCode
        connection.disconnect()
        return code
    }

    private fun put(url: String, username: String, password: String, body: ByteArray) {
        val connection = openConnection(url, username, password, "PUT")
        connection.doOutput = true
        connection.setFixedLengthStreamingMode(body.size)
        connection.outputStream.use { it.write(body) }
        val code = connection.responseCode
        connection.disconnect()
        if (code !in 200..299) error("Upload failed: HTTP $code")
    }

    private fun openConnection(
        url: String,
        username: String,
        password: String,
        method: String,
    ): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = 20_000
        connection.readTimeout = 60_000
        val token = Base64.getEncoder().encodeToString("$username:$password".toByteArray(Charsets.UTF_8))
        connection.setRequestProperty("Authorization", "Basic $token")
        return connection
    }
}
