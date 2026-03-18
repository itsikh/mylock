package com.mylock.app.backup

import android.app.NotificationManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.mylock.app.logging.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Transparent, no-UI activity that opens the system file picker so the user can choose
 * where to save a pending backup ZIP file.
 *
 * ## Purpose
 * When a backup is built programmatically (e.g. triggered by an event), the app cannot
 * directly open a file picker because it may not have a foreground Activity. Instead,
 * it shows a notification with a "Save" action that starts this Activity. This Activity
 * immediately launches [ActivityResultContracts.CreateDocument], which presents the full
 * Android Storage Access Framework (SAF) picker — including Google Drive, Dropbox,
 * local storage, USB drives, and any other installed storage provider.
 *
 * ## Flow
 * 1. The backup manager creates a temp ZIP in `cacheDir/pending_backups/`.
 * 2. A notification is shown with a `PendingIntent` targeting this activity, passing the
 *    temp file path, suggested filename, and notification ID as extras.
 * 3. User taps the notification → this activity starts → SAF picker opens.
 * 4. On URI selection: the temp file is copied to the chosen URI, then deleted.
 * 5. The notification is dismissed and this activity finishes.
 * 6. If the user cancels the picker, the temp file is left for the OS to clean up.
 *
 * ## Registration
 * Must be declared in `AndroidManifest.xml` with `android:exported="false"` and
 * `android:launchMode="singleInstance"` to prevent duplicate picker dialogs.
 *
 * ## Extras (passed via Intent)
 * - [EXTRA_FILE_PATH] — absolute path of the temp ZIP file to save.
 * - [EXTRA_SUGGESTED_NAME] — filename suggested to the SAF picker (user can change it).
 * - [EXTRA_NOTIFICATION_ID] — ID of the notification to dismiss after saving.
 * - [EXTRA_DELETE_ON_SAVE] — if `true` (default), the temp file is deleted after a successful save.
 */
class BackupSaveActivity : ComponentActivity() {

    private var tempFilePath: String? = null
    private var notificationId: Int = 0
    private var deleteTempOnSave: Boolean = true

    private val saveLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        val path = tempFilePath
        if (uri != null && path != null) {
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    contentResolver.openOutputStream(uri, "wt")?.use { out ->
                        File(path).inputStream().use { it.copyTo(out) }
                    }
                    AppLogger.i(TAG, "Backup saved to $uri")
                } catch (e: Exception) {
                    AppLogger.w(TAG, "Failed to save backup: ${e.message}")
                } finally {
                    if (deleteTempOnSave) File(path).delete()
                    dismissNotification()
                    withContext(Dispatchers.Main) { finish() }
                }
            }
        } else {
            // User cancelled — leave temp file for OS to clean up from cache
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        tempFilePath     = intent.getStringExtra(EXTRA_FILE_PATH)
        notificationId   = intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0)
        deleteTempOnSave = intent.getBooleanExtra(EXTRA_DELETE_ON_SAVE, true)
        val suggestedName = intent.getStringExtra(EXTRA_SUGGESTED_NAME) ?: "backup.zip"

        val tempFile = tempFilePath?.let { File(it) }
        if (tempFile == null || !tempFile.exists()) {
            AppLogger.w(TAG, "Backup temp file missing: $tempFilePath")
            dismissNotification()
            finish()
            return
        }

        saveLauncher.launch(suggestedName)
    }

    private fun dismissNotification() {
        if (notificationId != 0) {
            getSystemService(NotificationManager::class.java)?.cancel(notificationId)
        }
    }

    companion object {
        /** Absolute path of the temp ZIP file to copy to the user-chosen location. */
        const val EXTRA_FILE_PATH = "extra_file_path"

        /** Filename to pre-fill in the SAF picker (user can rename). */
        const val EXTRA_SUGGESTED_NAME = "extra_suggested_name"

        /** Notification ID to cancel after a successful save. */
        const val EXTRA_NOTIFICATION_ID = "extra_notification_id"

        /** If `true`, deletes the temp file after a successful save. Default: `true`. */
        const val EXTRA_DELETE_ON_SAVE = "extra_delete_on_save"

        private const val TAG = "BackupSaveActivity"
    }
}
