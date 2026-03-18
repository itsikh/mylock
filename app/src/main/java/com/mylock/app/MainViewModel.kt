package com.mylock.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mylock.app.logging.AppLogger
import com.mylock.app.logging.DebugSettings
import com.mylock.app.update.AppUpdateManager
import com.mylock.app.update.UpdateInfo
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel scoped to [MainActivity] that handles the startup auto-update check.
 *
 * On init, reads [DebugSettings.autoUpdateEnabled] and — if enabled — calls
 * [AppUpdateManager.checkForUpdate] silently in the background. If a newer version is
 * found, [updatePrompt] emits [UpdatePromptState.Available] which triggers an AlertDialog
 * in [MainActivity]. The user can then approve to download-and-install, or dismiss.
 */
@HiltViewModel
class MainViewModel @Inject constructor(
    private val updateManager: AppUpdateManager,
    private val debugSettings: DebugSettings,
    @ApplicationContext private val context: Context
) : ViewModel() {

    sealed class UpdatePromptState {
        object None : UpdatePromptState()
        data class Available(val info: UpdateInfo) : UpdatePromptState()
        object Downloading : UpdatePromptState()
    }

    private val _updatePrompt = MutableStateFlow<UpdatePromptState>(UpdatePromptState.None)
    val updatePrompt: StateFlow<UpdatePromptState> = _updatePrompt

    init {
        viewModelScope.launch {
            val enabled = debugSettings.autoUpdateEnabled.first()
            if (enabled) {
                runStartupUpdateCheck()
            }
        }
    }

    private suspend fun runStartupUpdateCheck() {
        try {
            val update = updateManager.checkForUpdate()
            if (update != null) {
                _updatePrompt.value = UpdatePromptState.Available(update)
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "Startup update check failed: ${e.message}")
        }
    }

    fun downloadAndInstall(info: UpdateInfo) {
        viewModelScope.launch {
            if (!context.packageManager.canRequestPackageInstalls()) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}")
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                _updatePrompt.value = UpdatePromptState.None
                return@launch
            }
            _updatePrompt.value = UpdatePromptState.Downloading
            val apkFile = updateManager.downloadApk(info.downloadUrl)
            if (apkFile != null) {
                context.startActivity(updateManager.createInstallIntent(apkFile))
            } else {
                context.startActivity(updateManager.createBrowserDownloadIntent())
            }
            _updatePrompt.value = UpdatePromptState.None
        }
    }

    fun dismissUpdatePrompt() {
        _updatePrompt.value = UpdatePromptState.None
    }

    companion object {
        private const val TAG = "MainViewModel"
    }
}
