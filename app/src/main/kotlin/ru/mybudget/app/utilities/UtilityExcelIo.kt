package ru.mybudget.app.utilities

import android.content.ContentResolver
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.mybudget.app.data.UtilityDao

object UtilityExcelIo {
    suspend fun saveTemplate(resolver: ContentResolver, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            resolver.openOutputStream(uri)?.use { UtilityExcelExporter.exportMetersTemplate(it) }
                ?: error("no stream")
        }.isSuccess
    }

    suspend fun saveMeters(resolver: ContentResolver, uri: Uri, dao: UtilityDao): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                resolver.openOutputStream(uri)?.use { UtilityExcelExporter.exportMeters(dao, it) }
                    ?: error("no stream")
            }.isSuccess
        }

    suspend fun saveCommunal(resolver: ContentResolver, uri: Uri, dao: UtilityDao): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                resolver.openOutputStream(uri)?.use { UtilityExcelExporter.exportCommunal(dao, it) }
                    ?: error("no stream")
            }.isSuccess
        }

    suspend fun importMeters(
        resolver: ContentResolver,
        uri: Uri,
        dao: UtilityDao,
        replaceReadings: Boolean,
    ): Result<UtilityExcelImporter.MetersImportResult> = withContext(Dispatchers.IO) {
        runCatching {
            resolver.openInputStream(uri)?.use { input ->
                UtilityExcelImporter(dao).importMetersFromStream(input, replaceReadings)
            } ?: error("no stream")
        }
    }

    suspend fun importCommunal(
        resolver: ContentResolver,
        uri: Uri,
        dao: UtilityDao,
        replaceExisting: Boolean,
    ): Result<UtilityExcelImporter.ImportResult> = withContext(Dispatchers.IO) {
        runCatching {
            resolver.openInputStream(uri)?.use { input ->
                UtilityExcelImporter(dao).importFromStream(input, replaceExisting)
            } ?: error("no stream")
        }
    }
}
