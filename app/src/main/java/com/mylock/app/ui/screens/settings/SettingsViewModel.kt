package com.mylock.app.ui.screens.settings

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.mylock.app.bugreport.GitHubIssuesClient
import com.mylock.app.geofence.GeofenceBroadcastReceiver
import com.mylock.app.geofence.GeofenceManager
import com.mylock.app.logging.AppLogger
import com.mylock.app.logging.DebugSettings
import com.mylock.app.logging.LogLevel
import com.mylock.app.security.SecureKeyManager
import com.mylock.app.ttlock.TtlockLock
import com.mylock.app.ttlock.TtlockRepository
import com.mylock.app.ttlock.TtlockResult
import com.mylock.app.update.AppUpdateManager
import com.mylock.app.update.UpdateInfo
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import javax.inject.Inject

/**
 * ViewModel for the generic settings screen ([SettingsScreen]).
 *
 * Manages state for:
 * - **Admin mode** — toggled by the 7-tap easter egg in [ui.components.SettingsScaffold]
 * - **Log level** — DEBUG vs WARN, persisted via [DebugSettings]
 * - **Bug button visibility** — floating bug-report FAB shown/hidden
 * - **Auto-update** — whether [AppUpdateManager] checks on launch
 * - **Auto-backup** — whether the app backs up automatically after events
 * - **GitHub token** — PAT for bug reports and update checks
 * - **App update state machine** — Idle → Checking → Available/UpToDate → Downloading → Install
 * - **Backup export state machine** — Idle → Exporting → Done/Error
 * - **Backup restore state machine** — Idle → Restoring → Done/Error
 *
 * ## Backup integration
 * The backup export ([exportBackupToUri]) and restore ([restoreFromBackup]) methods contain
 * placeholder implementations that log a warning. To wire in your app's actual data:
 *
 * 1. Inject your concrete [backup.BaseBackupManager] subclass into this ViewModel.
 * 2. In [exportBackupToUri], call `backupManager.exportToUri(uri)`.
 * 3. In [restoreFromBackup], call `backupManager.importFromUri(uri)`.
 *
 * The SAF URI passed to these methods already handles both local storage and Google Drive —
 * no special Google Drive SDK is needed. Android routes the I/O through the correct provider.
 *
 * ## App update installation
 * [downloadAndInstall] checks `canRequestPackageInstalls()` before downloading. If the
 * permission has not been granted, it opens the system settings page for the user to enable it.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val debugSettings: DebugSettings,
    private val secureKeyManager: SecureKeyManager,
    private val updateManager: AppUpdateManager,
    private val ttlockRepository: TtlockRepository,
    private val geofenceManager: GeofenceManager,
    @ApplicationContext private val context: Context
) : ViewModel() {

    // ── Debug settings state ──────────────────────────────────────────────────

    val adminMode: StateFlow<Boolean> = debugSettings.adminMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val logLevel: StateFlow<LogLevel> = debugSettings.logLevel
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), LogLevel.INFO)

    val showBugButton: StateFlow<Boolean> = debugSettings.showBugButton
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val autoUpdateEnabled: StateFlow<Boolean> = debugSettings.autoUpdateEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val autoBackupEnabled: StateFlow<Boolean> = debugSettings.autoBackupEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    // ── GitHub token ──────────────────────────────────────────────────────────

    /** `true` if a GitHub PAT is currently stored in [SecureKeyManager]. */
    val hasGitHubToken: Boolean
        get() = secureKeyManager.hasKey(GitHubIssuesClient.KEY_GITHUB_TOKEN)

    /** Saves the GitHub PAT to [SecureKeyManager]. Trims whitespace before saving. */
    fun saveGitHubToken(token: String) {
        if (token.isNotBlank()) {
            secureKeyManager.saveKey(GitHubIssuesClient.KEY_GITHUB_TOKEN, token.trim())
            AppLogger.i(TAG, "GitHub token saved")
        }
    }

    /** Removes the GitHub PAT from [SecureKeyManager]. */
    fun clearGitHubToken() {
        secureKeyManager.deleteKey(GitHubIssuesClient.KEY_GITHUB_TOKEN)
        AppLogger.i(TAG, "GitHub token cleared")
    }

    // ── Settings toggles ──────────────────────────────────────────────────────

    fun setAdminMode(enabled: Boolean) {
        viewModelScope.launch { debugSettings.setAdminMode(enabled) }
    }

    fun setDetailedLogging(enabled: Boolean) {
        viewModelScope.launch {
            debugSettings.setLogLevel(if (enabled) LogLevel.DEBUG else LogLevel.WARN)
        }
    }

    fun setShowBugButton(show: Boolean) {
        viewModelScope.launch { debugSettings.setShowBugButton(show) }
    }

    fun setAutoUpdateEnabled(enabled: Boolean) {
        viewModelScope.launch { debugSettings.setAutoUpdateEnabled(enabled) }
    }

    fun setAutoBackupEnabled(enabled: Boolean) {
        viewModelScope.launch { debugSettings.setAutoBackupEnabled(enabled) }
    }

    /** Clears the in-memory [AppLogger] buffer. Does not affect crash log files on disk. */
    fun clearAllLogs() {
        AppLogger.clear()
        AppLogger.i(TAG, "Logs cleared by user")
    }

    // ── App update state ──────────────────────────────────────────────────────

    /**
     * State machine for the update check flow.
     * Displayed by the Auto-Update card in [SettingsScreen].
     */
    sealed class UpdateState {
        object Idle : UpdateState()
        object Checking : UpdateState()
        data class UpdateAvailable(val info: UpdateInfo) : UpdateState()
        object UpToDate : UpdateState()
        object Downloading : UpdateState()
        data class ReadyToInstall(val apkPath: String) : UpdateState()
        data class Error(val message: String) : UpdateState()
    }

    private val _updateState = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val updateState: StateFlow<UpdateState> = _updateState

    /** Checks GitHub Releases for a newer version. Updates [updateState]. */
    fun checkForUpdate() {
        viewModelScope.launch {
            _updateState.value = UpdateState.Checking
            try {
                val update = updateManager.checkForUpdate()
                _updateState.value = if (update != null) UpdateState.UpdateAvailable(update)
                                     else UpdateState.UpToDate
            } catch (e: Exception) {
                AppLogger.e(TAG, "Update check failed", e)
                _updateState.value = UpdateState.Error(e.message ?: "Unknown error")
            }
        }
    }

    /**
     * Downloads the APK for [info] and launches the system package installer.
     * If `REQUEST_INSTALL_PACKAGES` has not been granted, opens the system settings page first.
     */
    fun downloadAndInstall(info: UpdateInfo) {
        viewModelScope.launch {
            if (!context.packageManager.canRequestPackageInstalls()) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}")
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                _updateState.value = UpdateState.Error(
                    "Allow installing from this source in Settings, then try again"
                )
                return@launch
            }
            _updateState.value = UpdateState.Downloading
            val apkFile = updateManager.downloadApk(info.downloadUrl)
            if (apkFile != null) {
                _updateState.value = UpdateState.ReadyToInstall(apkFile.absolutePath)
                context.startActivity(updateManager.createInstallIntent(apkFile))
            } else {
                _updateState.value = UpdateState.Error("Download failed — opening browser instead")
                context.startActivity(updateManager.createBrowserDownloadIntent())
            }
        }
    }

    fun resetUpdateState() {
        _updateState.value = UpdateState.Idle
    }

    // ── Backup export state ───────────────────────────────────────────────────

    /**
     * State machine for the manual backup export flow.
     * [Done.itemCount] is whatever integer [exportBackupToUri] returns (e.g. number of records).
     */
    sealed class ExportState {
        object Idle : ExportState()
        object Exporting : ExportState()
        data class Done(val itemCount: Int) : ExportState()
        data class Error(val message: String) : ExportState()
    }

    private val _exportState = MutableStateFlow<ExportState>(ExportState.Idle)
    val exportState: StateFlow<ExportState> = _exportState

    /**
     * Exports app data to the SAF [uri] chosen by the user via [CreateDocument].
     *
     * The [uri] works transparently with any storage provider — local filesystem, Google Drive,
     * Dropbox, USB, etc. — without any extra SDK integration.
     *
     * **TODO**: Inject your [backup.BaseBackupManager] subclass and call `backupManager.exportToUri(uri)`.
     * Replace the placeholder body below with your actual backup logic.
     */
    fun exportBackupToUri(uri: Uri) {
        viewModelScope.launch {
            _exportState.value = ExportState.Exporting
            try {
                // TODO: Replace with your BackupManager call:
                //   backupManager.exportToUri(uri)
                // The base class handles ZIP creation and writing to the URI.
                AppLogger.w(TAG, "exportBackupToUri: No BackupManager wired up yet. See TODO in SettingsViewModel.")
                _exportState.value = ExportState.Done(itemCount = 0)
            } catch (e: Exception) {
                AppLogger.e(TAG, "Export failed", e)
                _exportState.value = ExportState.Error(e.message ?: "Export failed")
            }
        }
    }

    fun resetExportState() {
        _exportState.value = ExportState.Idle
    }

    // ── Backup restore state ──────────────────────────────────────────────────

    /**
     * State machine for the backup restore flow.
     * [Done.itemCount] is whatever integer [restoreFromBackup] returns (e.g. number of records restored).
     */
    sealed class RestoreState {
        object Idle : RestoreState()
        object Restoring : RestoreState()
        data class Done(val itemCount: Int) : RestoreState()
        data class Error(val message: String) : RestoreState()
    }

    private val _restoreState = MutableStateFlow<RestoreState>(RestoreState.Idle)
    val restoreState: StateFlow<RestoreState> = _restoreState

    /**
     * Restores app data from the backup ZIP at the SAF [uri] chosen by the user via [OpenDocument].
     *
     * **TODO**: Inject your [backup.BaseBackupManager] subclass and call `backupManager.importFromUri(uri)`.
     * Replace the placeholder body below with your actual restore logic.
     */
    fun restoreFromBackup(uri: Uri) {
        viewModelScope.launch {
            _restoreState.value = RestoreState.Restoring
            try {
                // TODO: Replace with your BackupManager call:
                //   backupManager.importFromUri(uri)
                // The base class handles ZIP extraction, JSON parsing, and data insertion.
                AppLogger.w(TAG, "restoreFromBackup: No BackupManager wired up yet. See TODO in SettingsViewModel.")
                _restoreState.value = RestoreState.Done(itemCount = 0)
            } catch (e: Exception) {
                AppLogger.e(TAG, "Restore failed", e)
                _restoreState.value = RestoreState.Error(e.message ?: "Restore failed")
            }
        }
    }

    fun resetRestoreState() {
        _restoreState.value = RestoreState.Idle
    }

    // ── TTLock developer credentials (client_id / client_secret) ─────────────

    val hasClientCredentials: Boolean get() = ttlockRepository.hasClientCredentials()

    fun saveClientCredentials(clientId: String, clientSecret: String) {
        ttlockRepository.saveClientCredentials(clientId, clientSecret)
    }

    // ── TTLock user credentials ───────────────────────────────────────────────

    val hasCredentials: Boolean get() = ttlockRepository.hasCredentials()
    val selectedLockName: String? get() = ttlockRepository.getSelectedLockName()

    sealed class CredentialSaveState {
        object Idle : CredentialSaveState()
        object Validating : CredentialSaveState()
        data class Error(val message: String) : CredentialSaveState()
    }

    private val _credentialSaveState = MutableStateFlow<CredentialSaveState>(CredentialSaveState.Idle)
    val credentialSaveState: StateFlow<CredentialSaveState> = _credentialSaveState

    fun saveTtlockCredentials(username: String, password: String) {
        viewModelScope.launch {
            _credentialSaveState.value = CredentialSaveState.Validating
            when (val r = ttlockRepository.validateAndSaveCredentials(username, password)) {
                is TtlockResult.Success -> _credentialSaveState.value = CredentialSaveState.Idle
                is TtlockResult.Error -> _credentialSaveState.value = CredentialSaveState.Error(r.message)
            }
        }
    }

    fun resetCredentialSaveState() {
        _credentialSaveState.value = CredentialSaveState.Idle
    }

    sealed class LockListState {
        object Idle : LockListState()
        object Loading : LockListState()
        data class Loaded(val locks: List<TtlockLock>) : LockListState()
        data class Error(val message: String) : LockListState()
    }

    private val _lockListState = MutableStateFlow<LockListState>(LockListState.Idle)
    val lockListState: StateFlow<LockListState> = _lockListState

    fun loadLocks() {
        viewModelScope.launch {
            _lockListState.value = LockListState.Loading
            when (val r = ttlockRepository.getLocks()) {
                is TtlockResult.Success -> _lockListState.value = LockListState.Loaded(r.data)
                is TtlockResult.Error -> _lockListState.value = LockListState.Error(r.message)
            }
        }
    }

    fun selectLock(lock: TtlockLock) {
        ttlockRepository.saveSelectedLock(lock.lockId, lock.lockAlias.ifEmpty { lock.lockName })
        _lockListState.value = LockListState.Idle
        AppLogger.i(TAG, "Selected lock: ${lock.lockAlias} (${lock.lockId})")
    }

    // ── Home Location ─────────────────────────────────────────────────────────

    sealed class HomeLocationState {
        object Idle : HomeLocationState()
        object Fetching : HomeLocationState()
        data class Set(val lat: Double, val lng: Double) : HomeLocationState()
        data class Error(val message: String) : HomeLocationState()
    }

    private fun loadedHomeLocationState(): HomeLocationState {
        val prefs = context.getSharedPreferences("home_location", Context.MODE_PRIVATE)
        val lat = prefs.getFloat("lat", Float.MIN_VALUE)
        val lng = prefs.getFloat("lng", Float.MIN_VALUE)
        return if (lat != Float.MIN_VALUE && lng != Float.MIN_VALUE)
            HomeLocationState.Set(lat.toDouble(), lng.toDouble())
        else HomeLocationState.Idle
    }

    private val _homeLocationState = MutableStateFlow(loadedHomeLocationState())
    val homeLocationState: StateFlow<HomeLocationState> = _homeLocationState

    @SuppressLint("MissingPermission")
    fun setHomeFromCurrentLocation() {
        viewModelScope.launch {
            _homeLocationState.value = HomeLocationState.Fetching
            try {
                val client = LocationServices.getFusedLocationProviderClient(context)
                val cts = CancellationTokenSource()
                val location = suspendCancellableCoroutine { cont ->
                    cont.invokeOnCancellation { cts.cancel() }
                    client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cts.token)
                        .addOnSuccessListener { cont.resume(it) }
                        .addOnFailureListener { cont.resume(null) }
                }
                if (location != null) {
                    val lat = location.latitude
                    val lng = location.longitude
                    geofenceManager.registerHomeGeofence(lat, lng)
                    // Persist lat/lng so we can display it on next launch
                    context.getSharedPreferences("home_location", Context.MODE_PRIVATE)
                        .edit().putFloat("lat", lat.toFloat()).putFloat("lng", lng.toFloat()).apply()
                    // User is physically at home right now — unlock immediately
                    context.getSharedPreferences("geofence_state", Context.MODE_PRIVATE)
                        .edit().putBoolean(GeofenceBroadcastReceiver.PREF_IS_NEAR_HOME, true).apply()
                    AppLogger.i(TAG, "Home location set to $lat,$lng")
                    _homeLocationState.value = HomeLocationState.Set(lat, lng)
                } else {
                    _homeLocationState.value = HomeLocationState.Error(
                        "Could not get location. Enable GPS and try again."
                    )
                }
            } catch (e: SecurityException) {
                _homeLocationState.value = HomeLocationState.Error("Location permission required")
            } catch (e: Exception) {
                AppLogger.e(TAG, "setHomeFromCurrentLocation failed", e)
                _homeLocationState.value = HomeLocationState.Error(e.message ?: "Unknown error")
            }
        }
    }

    fun setHomeLocation(lat: Double, lng: Double) {
        geofenceManager.registerHomeGeofence(lat, lng)
        AppLogger.i(TAG, "Home location set to $lat,$lng")
    }

    companion object {
        private const val TAG = "SettingsViewModel"
    }
}
