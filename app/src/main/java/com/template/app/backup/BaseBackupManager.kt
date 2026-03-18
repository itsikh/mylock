package com.template.app.backup

import android.content.Context
import android.net.Uri
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.template.app.logging.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Backup data container. Apps provide their data as a JsonElement (serialized by Gson)
 * along with optional attachment files to include in the ZIP.
 */
data class BackupData(
    val version: Int = 1,
    val timestamp: Long = System.currentTimeMillis(),
    val data: JsonElement,
    val attachmentFiles: List<File> = emptyList()
)

/**
 * Wrapper for backup JSON content read from a ZIP file.
 */
data class BackupContent(
    val version: Int,
    val timestamp: Long,
    val data: JsonElement
)

/**
 * Abstract backup manager providing ZIP-based data export/import.
 *
 * Subclasses implement [collectBackupData] to gather app-specific data
 * and [restoreBackupData] to restore it. This base class handles
 * ZIP creation, JSON serialization, URI I/O via ContentResolver,
 * and attachment file management.
 *
 * @param context Application context
 * @param appName Used for backup filename pattern: {appName}_backup_yyyyMMdd_HHmmss.zip
 */
abstract class BaseBackupManager(
    protected val context: Context,
    private val appName: String
) {
    protected val gson: Gson = GsonBuilder().setPrettyPrinting().create()

    /**
     * Collect all app data to back up. Implementations should query
     * Room DAOs, read files, etc. and return the result as a [BackupData].
     */
    abstract suspend fun collectBackupData(): BackupData

    /**
     * Restore app data from a backup. Implementations should clear existing
     * data and insert the restored data within a Room transaction.
     *
     * @param data The parsed backup content
     * @param extractDir Directory where attachment files have been extracted (if any)
     */
    abstract suspend fun restoreBackupData(data: BackupContent, extractDir: File)

    /**
     * Export backup data to a ZIP file and write it to the given URI
     * via ContentResolver (for SAF integration).
     */
    suspend fun exportToUri(uri: Uri) {
        val zipFile = exportToZip()
        withContext(Dispatchers.IO) {
            try {
                context.contentResolver.openOutputStream(uri)?.use { output ->
                    zipFile.inputStream().use { input ->
                        input.copyTo(output)
                    }
                } ?: throw Exception("Cannot write to selected file")
            } finally {
                zipFile.delete()
            }
        }
    }

    /**
     * Import backup data from a ZIP file read from the given URI
     * via ContentResolver (for SAF integration).
     */
    suspend fun importFromUri(uri: Uri) {
        withContext(Dispatchers.IO) {
            val tmpFile = File(context.cacheDir, "restore_import_${System.currentTimeMillis()}.zip")
            try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    tmpFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                } ?: throw Exception("Cannot read the backup file")
                importFromZip(tmpFile)
            } finally {
                tmpFile.delete()
            }
        }
    }

    /**
     * Generate a backup filename with timestamp.
     * Pattern: {appName}_backup_yyyyMMdd_HHmmss.zip
     */
    fun generateBackupFileName(): String {
        val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        val timestamp = dateFormat.format(Date())
        return "${appName}_backup_$timestamp.zip"
    }

    private suspend fun exportToZip(): File {
        return withContext(Dispatchers.IO) {
            AppLogger.i(TAG, "Starting backup export...")

            val backupData = collectBackupData()

            val backupJson = BackupContent(
                version = backupData.version,
                timestamp = backupData.timestamp,
                data = backupData.data
            )
            val json = gson.toJson(backupJson)

            val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
            val timestamp = dateFormat.format(Date())
            val zipFile = File(context.cacheDir, "${appName}_backup_$timestamp.zip")

            ZipOutputStream(BufferedOutputStream(FileOutputStream(zipFile))).use { zos ->
                // Add JSON data
                zos.putNextEntry(ZipEntry("backup.json"))
                zos.write(json.toByteArray(Charsets.UTF_8))
                zos.closeEntry()

                // Add attachment files
                for (file in backupData.attachmentFiles) {
                    if (file.exists()) {
                        val relativePath = getRelativePath(file.absolutePath)
                        zos.putNextEntry(ZipEntry(relativePath))
                        FileInputStream(file).use { fis ->
                            fis.copyTo(zos)
                        }
                        zos.closeEntry()
                    }
                }
            }

            AppLogger.i(TAG, "Backup exported: ${zipFile.name} (${zipFile.length()} bytes)")
            zipFile
        }
    }

    private suspend fun importFromZip(zipFile: File) {
        withContext(Dispatchers.IO) {
            AppLogger.i(TAG, "Starting backup import from ${zipFile.name}...")

            val extractDir = File(context.cacheDir, "backup_extract_${System.currentTimeMillis()}")
            extractDir.mkdirs()

            try {
                // Extract ZIP
                ZipInputStream(FileInputStream(zipFile)).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        val outputFile = File(extractDir, entry.name)
                        outputFile.parentFile?.mkdirs()
                        if (!entry.isDirectory) {
                            FileOutputStream(outputFile).use { fos ->
                                zis.copyTo(fos)
                            }
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }

                // Parse JSON
                val jsonFile = File(extractDir, "backup.json")
                if (!jsonFile.exists()) {
                    throw Exception("Invalid backup file: missing backup.json")
                }

                val json = jsonFile.readText(Charsets.UTF_8)
                val backupContent = gson.fromJson(json, BackupContent::class.java)

                // Delegate to subclass for actual data restoration
                restoreBackupData(backupContent, extractDir)

                AppLogger.i(TAG, "Backup import completed successfully")
            } finally {
                extractDir.deleteRecursively()
            }
        }
    }

    /**
     * Convert an absolute file path to a relative path for ZIP storage.
     * Files under filesDir get their prefix stripped; others go under "attachments/".
     */
    protected fun getRelativePath(absolutePath: String): String {
        val filesDir = context.filesDir.absolutePath
        return if (absolutePath.startsWith(filesDir)) {
            absolutePath.removePrefix("$filesDir/")
        } else {
            "attachments/${File(absolutePath).name}"
        }
    }

    companion object {
        private const val TAG = "BaseBackupManager"
    }
}
