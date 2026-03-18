package com.mylock.app.backup

import android.content.Context
import android.net.Uri
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.mylock.app.logging.AppLogger
import com.mylock.app.security.EncryptedData
import com.mylock.app.security.EncryptionHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Container for settings data to be backed up.
 * Apps provide their settings as a JsonObject.
 */
data class SettingsData(
    val version: Int = 1,
    val timestamp: Long = System.currentTimeMillis(),
    val data: JsonObject
)

/**
 * Abstract settings backup manager providing encrypted settings export/import.
 *
 * Subclasses implement [collectSettingsData] to gather app-specific settings
 * and [restoreSettingsData] to restore them. This base class handles
 * JSON serialization, AES-256-GCM encryption via [EncryptionHelper],
 * and URI I/O via ContentResolver.
 *
 * File format: [16-byte salt][12-byte IV][ciphertext+tag]
 *
 * @param context Application context
 * @param appName Used for settings filename pattern: {appName}_settings_yyyyMMdd_HHmmss.{appName}_settings
 */
abstract class BaseSettingsBackupManager(
    protected val context: Context,
    private val appName: String
) {
    protected val gson: Gson = GsonBuilder().setPrettyPrinting().create()

    /**
     * Collect all app settings to back up. Implementations should read
     * API keys, preferences, debug settings, etc. and return as [SettingsData].
     */
    abstract suspend fun collectSettingsData(): SettingsData

    /**
     * Restore app settings from a backup. Implementations should apply
     * the restored settings (save API keys, update preferences, etc.).
     *
     * @param data The parsed settings JSON object
     */
    abstract suspend fun restoreSettingsData(data: JsonObject)

    /**
     * Exports settings to an encrypted file and writes it to the given URI.
     *
     * @param uri The URI where the encrypted backup should be saved
     * @param password The password to encrypt the backup with
     * @throws IOException if file cannot be written
     */
    suspend fun exportSettingsToUri(uri: Uri, password: String) = withContext(Dispatchers.IO) {
        AppLogger.i(TAG, "Starting settings export...")

        val settingsData = collectSettingsData()

        // Wrap in envelope with version and timestamp
        val envelope = JsonObject().apply {
            addProperty("version", settingsData.version)
            addProperty("timestamp", settingsData.timestamp)
            add("data", settingsData.data)
        }

        val json = gson.toJson(envelope)

        // Encrypt with password
        val encrypted = EncryptionHelper.encrypt(json, password)

        // Write to URI: [salt][iv][ciphertext+tag]
        context.contentResolver.openOutputStream(uri)?.use { output ->
            output.write(encrypted.salt)
            output.write(encrypted.iv)
            output.write(encrypted.ciphertext)
        } ?: throw IOException("Cannot write to selected file")

        AppLogger.i(TAG, "Settings exported successfully")
    }

    /**
     * Imports settings from an encrypted file read from the given URI.
     *
     * @param uri The URI of the encrypted backup file
     * @param password The password to decrypt the backup
     * @throws IOException if file cannot be read or is invalid
     * @throws javax.crypto.AEADBadTagException if password is incorrect or data is tampered
     */
    suspend fun importSettingsFromUri(uri: Uri, password: String) = withContext(Dispatchers.IO) {
        AppLogger.i(TAG, "Starting settings import...")

        // Read encrypted file
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IOException("Cannot read the backup file")

        // Minimum size: 16 (salt) + 12 (IV) + at least 1 byte ciphertext
        if (bytes.size < 29) {
            throw IOException("Invalid backup file - file too small")
        }

        // Extract salt, IV, ciphertext
        val salt = bytes.copyOfRange(0, 16)
        val iv = bytes.copyOfRange(16, 28)
        val ciphertext = bytes.copyOfRange(28, bytes.size)

        // Decrypt (throws AEADBadTagException if wrong password)
        val json = EncryptionHelper.decrypt(EncryptedData(salt, iv, ciphertext), password)

        // Parse JSON envelope
        val envelope = try {
            gson.fromJson(json, JsonObject::class.java)
        } catch (e: Exception) {
            throw IOException("Invalid backup file - JSON parse error: ${e.message}")
        } ?: throw IOException("Invalid backup file - empty JSON")

        // Extract data object for subclass
        val data = envelope.getAsJsonObject("data")
            ?: throw IOException("Invalid backup file - missing data section")

        // Delegate to subclass for actual settings restoration
        restoreSettingsData(data)

        AppLogger.i(TAG, "Settings import completed successfully")
    }

    /**
     * Generate a filename for settings backup.
     * Pattern: {appName}_settings_yyyyMMdd_HHmmss.{appName}_settings
     */
    fun generateSettingsBackupFileName(): String {
        val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        return "${appName}_settings_${dateFormat.format(Date())}.${appName}_settings"
    }

    companion object {
        private const val TAG = "BaseSettingsBackupManager"
    }
}
