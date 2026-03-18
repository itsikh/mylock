package com.mylock.app.logging

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import javax.inject.Inject
import javax.inject.Singleton

/** DataStore instance scoped to the Application context, backed by the file "debug_settings". */
private val Context.debugDataStore: DataStore<Preferences> by preferencesDataStore(name = "debug_settings")

/**
 * Persistent store for developer/debug settings, backed by Jetpack DataStore.
 *
 * All settings are exposed as [Flow]s so UI can react to changes in real time.
 * Write operations are `suspend` functions and must be called from a coroutine.
 *
 * ## Settings
 * | Setting | Default | Purpose |
 * |---------|---------|---------|
 * | [logLevel] | DEBUG (debug) / WARN (release) | Controls [AppLogger] verbosity |
 * | [showBugButton] | `true` | Shows/hides the floating bug-report FAB |
 * | [autoUpdateEnabled] | `true` | Whether [update.AppUpdateManager] checks on launch |
 * | [adminMode] | `false` | Unlocks the hidden debug section in [ui.components.SettingsScaffold] |
 *
 * ## Startup sync
 * The `init` block reads the persisted [logLevel] synchronously (via `runBlocking`) and
 * applies it to [AppLogger.currentLevel] immediately. This ensures that even the first
 * log lines after app start use the correct level, not just the compile-time default.
 *
 * ## DataStore file
 * Settings are stored in `datastore/debug_settings.preferences_pb` in the app's data directory.
 * They survive app updates and are cleared only when the user clears app data.
 */
@Singleton
class DebugSettings @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val logLevelKey = stringPreferencesKey("log_level")
    private val showBugButtonKey = booleanPreferencesKey("show_bug_button")
    private val autoUpdateKey = booleanPreferencesKey("auto_update_enabled")
    private val autoBackupKey = booleanPreferencesKey("auto_backup_enabled")
    private val adminModeKey = booleanPreferencesKey("admin_mode_enabled")

    /**
     * The current [LogLevel] as a [Flow]. Emits a new value whenever the level changes.
     * Defaults to [LogLevel.DEBUG] in debug builds and [LogLevel.WARN] in release builds
     * if no value has been persisted yet.
     */
    val logLevel: Flow<LogLevel> = context.debugDataStore.data.map { prefs ->
        val levelName = prefs[logLevelKey]
        if (levelName != null) {
            try {
                LogLevel.valueOf(levelName)
            } catch (_: IllegalArgumentException) {
                defaultLogLevel()
            }
        } else {
            defaultLogLevel()
        }
    }

    init {
        // Apply persisted log level immediately so AppLogger is correct from app start
        runBlocking { AppLogger.currentLevel = logLevel.first() }
    }

    /**
     * Whether the floating bug-report button is visible on screen.
     * Defaults to `true` so developers always have easy access during testing.
     * Users can hide it via the admin-only debug section in settings.
     */
    val showBugButton: Flow<Boolean> = context.debugDataStore.data.map { prefs ->
        prefs[showBugButtonKey] ?: true
    }

    /**
     * Whether [update.AppUpdateManager] should automatically check for new releases on launch.
     * Defaults to `true`. Can be disabled in the admin debug section for testing.
     */
    val autoUpdateEnabled: Flow<Boolean> = context.debugDataStore.data.map { prefs ->
        prefs[autoUpdateKey] ?: true
    }

    /**
     * Whether the app should automatically trigger a backup after significant data events
     * (e.g. import, bulk edit). Defaults to `false`.
     * When `true`, concrete [backup.BaseBackupManager] subclasses can observe this flag
     * to decide whether to schedule a background backup.
     */
    val autoBackupEnabled: Flow<Boolean> = context.debugDataStore.data.map { prefs ->
        prefs[autoBackupKey] ?: false
    }

    /**
     * Whether admin/developer mode is active. When `true`, the hidden debug section in
     * [ui.components.SettingsScaffold] is visible. Toggled by tapping the version string
     * 7 times via [ui.components.AdminModeHelper].
     * Defaults to `false`.
     */
    val adminMode: Flow<Boolean> = context.debugDataStore.data.map { prefs ->
        prefs[adminModeKey] ?: false
    }

    /** Persists the admin mode flag. Call from a coroutine (e.g. `viewModelScope.launch`). */
    suspend fun setAdminMode(enabled: Boolean) {
        context.debugDataStore.edit { prefs ->
            prefs[adminModeKey] = enabled
        }
    }

    /**
     * Persists the log level and immediately applies it to [AppLogger.currentLevel].
     * Call from a coroutine. After this returns, all subsequent [AppLogger] calls will
     * use the new level.
     */
    suspend fun setLogLevel(level: LogLevel) {
        context.debugDataStore.edit { prefs ->
            prefs[logLevelKey] = level.name
        }
        AppLogger.currentLevel = level
    }

    /** Persists the bug-button visibility setting. Call from a coroutine. */
    suspend fun setShowBugButton(show: Boolean) {
        context.debugDataStore.edit { prefs ->
            prefs[showBugButtonKey] = show
        }
    }

    /** Persists the auto-update enabled flag. Call from a coroutine. */
    suspend fun setAutoUpdateEnabled(enabled: Boolean) {
        context.debugDataStore.edit { prefs ->
            prefs[autoUpdateKey] = enabled
        }
    }

    /** Persists the auto-backup enabled flag. Call from a coroutine. */
    suspend fun setAutoBackupEnabled(enabled: Boolean) {
        context.debugDataStore.edit { prefs ->
            prefs[autoBackupKey] = enabled
        }
    }

    private fun defaultLogLevel(): LogLevel {
        return if (com.template.app.BuildConfig.DEBUG) LogLevel.DEBUG else LogLevel.WARN
    }
}
