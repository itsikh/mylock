package com.mylock.app.ui.screens.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Feedback
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RestoreFromTrash
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.mylock.app.AppConfig
import com.mylock.app.BuildConfig
import com.mylock.app.ui.components.SectionHeader
import com.mylock.app.ui.components.SettingsScaffold
import com.mylock.app.ui.screens.bugreport.ReportMode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Full-featured settings screen for the template app.
 *
 * ## Sections
 * | Section | Purpose |
 * |---------|---------|
 * | **GitHub Token** | Configure the PAT used for bug reports and update checks |
 * | **Auto-Update** | Check for and install a new release from GitHub Releases |
 * | **Backup** | Export all data to any storage (local / Google Drive / Dropbox) and restore |
 * | **Support** | Open the bug report screen, send feedback, clear logs |
 * | **Debug** | Admin-only: log level toggle, bug button visibility (via [SettingsScaffold]) |
 * | **About** | App name and version (tap 7× to unlock admin mode) |
 *
 * ## Backup
 * The Export and Restore buttons use [ActivityResultContracts.CreateDocument] and
 * [ActivityResultContracts.OpenDocument] respectively. Android's Storage Access Framework
 * automatically presents all available storage providers — including Google Drive, Dropbox,
 * and local filesystem — without any extra SDK integration.
 *
 * To wire in your actual data, edit [SettingsViewModel.exportBackupToUri] and
 * [SettingsViewModel.restoreFromBackup] to call your concrete [backup.BaseBackupManager].
 *
 * @param onBack Called when the user taps the back arrow.
 * @param onOpenBugReport Called when the user taps "Report a Bug" or "Send Feedback",
 *                        with the appropriate [ReportMode].
 * @param viewModel Injected by Hilt via `hiltViewModel()`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenBugReport: (ReportMode) -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val adminMode       by viewModel.adminMode.collectAsState()
    val logLevel        by viewModel.logLevel.collectAsState()
    val showBugButton   by viewModel.showBugButton.collectAsState()
    val autoUpdate      by viewModel.autoUpdateEnabled.collectAsState()
    val autoBackup      by viewModel.autoBackupEnabled.collectAsState()
    val updateState     by viewModel.updateState.collectAsState()
    val exportState     by viewModel.exportState.collectAsState()
    val restoreState    by viewModel.restoreState.collectAsState()
    val lockListState        by viewModel.lockListState.collectAsState()
    val credentialSaveState  by viewModel.credentialSaveState.collectAsState()

    // SAF launchers — CreateDocument shows all providers including Google Drive
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri: Uri? -> if (uri != null) viewModel.exportBackupToUri(uri) }

    val restoreLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? -> if (uri != null) viewModel.restoreFromBackup(uri) }

    // Local UI state
    var githubToken           by remember { mutableStateOf("") }
    var tokenVisible          by remember { mutableStateOf(false) }
    var hasToken              by remember { mutableStateOf(viewModel.hasGitHubToken) }
    var showRestoreDialog     by remember { mutableStateOf(false) }
    var showClearLogsDialog   by remember { mutableStateOf(false) }
    var logsCleared           by remember { mutableStateOf(false) }

    // TTLock local UI state
    var ttlockUsername        by remember { mutableStateOf("") }
    var ttlockPassword        by remember { mutableStateOf("") }
    var ttlockPasswordVisible by remember { mutableStateOf(false) }
    var hasCreds              by remember { mutableStateOf(viewModel.hasCredentials) }
    var selectedLockName      by remember { mutableStateOf(viewModel.selectedLockName) }

    // Clear form and refresh hasCreds after a successful credential save
    LaunchedEffect(credentialSaveState) {
        if (credentialSaveState is SettingsViewModel.CredentialSaveState.Idle) {
            val nowHasCreds = viewModel.hasCredentials
            if (nowHasCreds && !hasCreds) {
                hasCreds = true
                ttlockUsername = ""
                ttlockPassword = ""
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SettingsScaffold(
                appName = AppConfig.APP_NAME,
                versionName = BuildConfig.VERSION_NAME,
                adminMode = adminMode,
                logLevel = logLevel,
                showBugButton = showBugButton,
                onAdminModeToggle = { viewModel.setAdminMode(it) },
                onDetailedLoggingToggle = { viewModel.setDetailedLogging(it) },
                onShowBugButtonToggle = { viewModel.setShowBugButton(it) }
            ) {

                // ── TTLock Account ────────────────────────────────────────────
                SectionHeader("TTLock Account")
                Spacer(Modifier.height(8.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = if (!hasCreds || selectedLockName == null)
                        CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                    else
                        CardDefaults.cardColors()
                ) {
                    Column(
                        Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // Status row
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                if (hasCreds && selectedLockName != null) Icons.Default.CheckCircle else Icons.Default.Warning,
                                contentDescription = null,
                                tint = if (hasCreds && selectedLockName != null) MaterialTheme.colorScheme.tertiary
                                       else MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                when {
                                    !hasCreds -> "TTLock credentials required"
                                    selectedLockName == null -> "Credentials saved — select a lock below"
                                    else -> "Lock: $selectedLockName"
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (hasCreds && selectedLockName != null) MaterialTheme.colorScheme.tertiary
                                        else MaterialTheme.colorScheme.onErrorContainer
                            )
                        }

                        // Credentials fields
                        OutlinedTextField(
                            value = ttlockUsername,
                            onValueChange = { ttlockUsername = it },
                            label = { Text("TTLock Username") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = ttlockPassword,
                            onValueChange = { ttlockPassword = it },
                            label = { Text("TTLock Password") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            visualTransformation = if (ttlockPasswordVisible) VisualTransformation.None
                                                   else PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            trailingIcon = {
                                IconButton(onClick = { ttlockPasswordVisible = !ttlockPasswordVisible }) {
                                    Icon(
                                        if (ttlockPasswordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                        contentDescription = "Toggle visibility"
                                    )
                                }
                            }
                        )
                        val isValidating = credentialSaveState is SettingsViewModel.CredentialSaveState.Validating
                        Button(
                            onClick = {
                                viewModel.saveTtlockCredentials(ttlockUsername.trim(), ttlockPassword)
                            },
                            enabled = ttlockUsername.isNotBlank() && ttlockPassword.isNotBlank() && !isValidating,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            if (isValidating) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                                Spacer(Modifier.width(8.dp))
                                Text("Verifying…")
                            } else {
                                Text("Save Credentials")
                            }
                        }
                        if (credentialSaveState is SettingsViewModel.CredentialSaveState.Error) {
                            Text(
                                (credentialSaveState as SettingsViewModel.CredentialSaveState.Error).message,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall
                            )
                            TextButton(onClick = { viewModel.resetCredentialSaveState() }) {
                                Text("Dismiss")
                            }
                        }

                        // Lock selection
                        if (hasCreds) {
                            HorizontalDivider()
                            Text("Select Lock", style = MaterialTheme.typography.labelLarge)

                            when (val state = lockListState) {
                                is SettingsViewModel.LockListState.Idle -> {
                                    Button(
                                        onClick = { viewModel.loadLocks() },
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Icon(Icons.Default.Lock, null)
                                        Spacer(Modifier.width(8.dp))
                                        Text("Load My Locks")
                                    }
                                }
                                is SettingsViewModel.LockListState.Loading -> {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        CircularProgressIndicator(Modifier.size(20.dp))
                                        Text("Loading locks…")
                                    }
                                }
                                is SettingsViewModel.LockListState.Loaded -> {
                                    if (state.locks.isEmpty()) {
                                        Text(
                                            "No locks found on this account.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    } else {
                                        state.locks.forEach { lock ->
                                            OutlinedButton(
                                                onClick = {
                                                    viewModel.selectLock(lock)
                                                    selectedLockName = viewModel.selectedLockName
                                                },
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Text(lock.lockAlias.ifEmpty { lock.lockName })
                                            }
                                        }
                                    }
                                    TextButton(
                                        onClick = { viewModel.loadLocks() },
                                        modifier = Modifier.fillMaxWidth()
                                    ) { Text("Refresh") }
                                }
                                is SettingsViewModel.LockListState.Error -> {
                                    Text(
                                        state.message,
                                        color = MaterialTheme.colorScheme.error,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                    TextButton(
                                        onClick = { viewModel.loadLocks() },
                                        modifier = Modifier.fillMaxWidth()
                                    ) { Text("Retry") }
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))

                // ── GitHub Token ──────────────────────────────────────────────
                SectionHeader("GitHub Token")
                Spacer(Modifier.height(8.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = if (!hasToken)
                        CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                    else
                        CardDefaults.cardColors()
                ) {
                    Column(
                        Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                if (hasToken) Icons.Default.CheckCircle else Icons.Default.Warning,
                                contentDescription = null,
                                tint = if (hasToken) MaterialTheme.colorScheme.tertiary
                                       else MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                if (hasToken) "Token configured — updates and bug reports enabled"
                                else "Token required for updates and bug reports",
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (hasToken) MaterialTheme.colorScheme.tertiary
                                        else MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                        Text(
                            "A GitHub Personal Access Token with the \"repo\" scope is required to check for updates and submit bug reports. Generate one at: github.com → Settings → Developer settings → Personal access tokens",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (hasToken) MaterialTheme.colorScheme.onSurfaceVariant
                                    else MaterialTheme.colorScheme.onErrorContainer
                        )
                        OutlinedTextField(
                            value = githubToken,
                            onValueChange = { githubToken = it },
                            label = { Text("Personal Access Token") },
                            placeholder = { Text("ghp_...") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            visualTransformation = if (tokenVisible) VisualTransformation.None
                                                   else PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            trailingIcon = {
                                IconButton(onClick = { tokenVisible = !tokenVisible }) {
                                    Icon(
                                        if (tokenVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                        contentDescription = "Toggle visibility"
                                    )
                                }
                            }
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    viewModel.saveGitHubToken(githubToken)
                                    hasToken = viewModel.hasGitHubToken
                                    githubToken = ""
                                    tokenVisible = false
                                },
                                enabled = githubToken.isNotBlank(),
                                modifier = Modifier.weight(1f)
                            ) { Text("Save Token") }

                            if (hasToken) {
                                OutlinedButton(
                                    onClick = {
                                        viewModel.clearGitHubToken()
                                        hasToken = false
                                    },
                                    modifier = Modifier.weight(1f)
                                ) { Text("Clear") }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))

                // ── Auto-Update ───────────────────────────────────────────────
                SectionHeader("Auto-Update")
                Spacer(Modifier.height(8.dp))
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Check for Updates", style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    "Fetch latest release from GitHub on launch",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(checked = autoUpdate, onCheckedChange = { viewModel.setAutoUpdateEnabled(it) })
                        }

                        when (val state = updateState) {
                            is SettingsViewModel.UpdateState.Idle -> {
                                Button(
                                    onClick = { viewModel.checkForUpdate() },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(Icons.Default.Refresh, null)
                                    Spacer(Modifier.width(8.dp))
                                    Text("Check Now")
                                }
                            }
                            is SettingsViewModel.UpdateState.Checking -> {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    CircularProgressIndicator(Modifier.size(20.dp))
                                    Text("Checking for updates…")
                                }
                            }
                            is SettingsViewModel.UpdateState.UpToDate -> {
                                Text("App is up to date", color = MaterialTheme.colorScheme.tertiary)
                                TextButton(
                                    onClick = { viewModel.resetUpdateState() },
                                    modifier = Modifier.fillMaxWidth()
                                ) { Text("Check Again") }
                            }
                            is SettingsViewModel.UpdateState.UpdateAvailable -> {
                                Text(
                                    "Update available: v${state.info.version}",
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Button(
                                    onClick = { viewModel.downloadAndInstall(state.info) },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(Icons.Default.Download, null)
                                    Spacer(Modifier.width(8.dp))
                                    Text("Download & Install v${state.info.version}")
                                }
                            }
                            is SettingsViewModel.UpdateState.Downloading -> {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    CircularProgressIndicator(Modifier.size(20.dp))
                                    Text("Downloading update…")
                                }
                            }
                            is SettingsViewModel.UpdateState.ReadyToInstall ->
                                Text("Installation started…", color = MaterialTheme.colorScheme.tertiary)
                            is SettingsViewModel.UpdateState.Error -> {
                                Text(
                                    state.message,
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodySmall
                                )
                                TextButton(onClick = { viewModel.resetUpdateState() }) { Text("Dismiss") }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))

                // ── Backup ────────────────────────────────────────────────────
                SectionHeader("Backup & Restore")
                Spacer(Modifier.height(8.dp))
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // Auto-backup toggle
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Auto Backup", style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    "Automatically create a backup after key events. A notification lets you save it anywhere — Google Drive, Dropbox, or local storage.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            Switch(checked = autoBackup, onCheckedChange = { viewModel.setAutoBackupEnabled(it) })
                        }

                        HorizontalDivider()

                        // Manual export
                        Text("Export to Any Location", style = MaterialTheme.typography.labelLarge)
                        Text(
                            "Save a backup ZIP to any location — Google Drive, Dropbox, USB, or local storage. Android's file picker handles all providers automatically.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        when (val state = exportState) {
                            is SettingsViewModel.ExportState.Exporting -> {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    CircularProgressIndicator(Modifier.size(20.dp))
                                    Text("Exporting…", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            is SettingsViewModel.ExportState.Done -> {
                                Text(
                                    "Backup exported (${state.itemCount} items)",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.tertiary
                                )
                                TextButton(
                                    onClick = { viewModel.resetExportState() },
                                    modifier = Modifier.fillMaxWidth()
                                ) { Text("Export Again") }
                            }
                            is SettingsViewModel.ExportState.Error -> {
                                Text(
                                    "Export failed: ${state.message}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                                TextButton(
                                    onClick = { viewModel.resetExportState() },
                                    modifier = Modifier.fillMaxWidth()
                                ) { Text("Dismiss") }
                            }
                            else -> {
                                val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                                Button(
                                    onClick = {
                                        exportLauncher.launch("${AppConfig.APP_NAME.lowercase()}_backup_$ts.zip")
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(Icons.Default.CloudUpload, contentDescription = null)
                                    Spacer(Modifier.width(8.dp))
                                    Text("Export Backup Now")
                                }
                            }
                        }

                        HorizontalDivider()

                        // Restore
                        Text("Restore from Backup", style = MaterialTheme.typography.labelLarge)
                        Text(
                            "Restore from a previously exported backup ZIP. Existing data will be replaced.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        when (val state = restoreState) {
                            is SettingsViewModel.RestoreState.Restoring -> {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    CircularProgressIndicator(Modifier.size(20.dp))
                                    Text("Restoring…", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            is SettingsViewModel.RestoreState.Done -> {
                                Text(
                                    "Restored successfully (${state.itemCount} items)",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.tertiary
                                )
                                TextButton(
                                    onClick = { viewModel.resetRestoreState() },
                                    modifier = Modifier.fillMaxWidth()
                                ) { Text("Dismiss") }
                            }
                            is SettingsViewModel.RestoreState.Error -> {
                                Text(
                                    "Restore failed: ${state.message}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                                TextButton(
                                    onClick = { viewModel.resetRestoreState() },
                                    modifier = Modifier.fillMaxWidth()
                                ) { Text("Dismiss") }
                            }
                            else -> {
                                OutlinedButton(
                                    onClick = { showRestoreDialog = true },
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        contentColor = MaterialTheme.colorScheme.error
                                    )
                                ) {
                                    Icon(Icons.Default.RestoreFromTrash, contentDescription = null)
                                    Spacer(Modifier.width(8.dp))
                                    Text("Restore from Backup…")
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))

                // ── Support ───────────────────────────────────────────────────
                SectionHeader("Support")
                Spacer(Modifier.height(8.dp))
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { onOpenBugReport(ReportMode.BUG_REPORT) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.BugReport, null)
                            Spacer(Modifier.width(8.dp))
                            Text("Report a Bug")
                        }
                        OutlinedButton(
                            onClick = { onOpenBugReport(ReportMode.USER_FEEDBACK) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Feedback, null)
                            Spacer(Modifier.width(8.dp))
                            Text("Send Feedback")
                        }
                        OutlinedButton(
                            onClick = { showClearLogsDialog = true },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            )
                        ) {
                            Icon(Icons.Default.DeleteSweep, null)
                            Spacer(Modifier.width(8.dp))
                            Text("Clear Logs")
                        }
                        if (logsCleared) {
                            Text(
                                "Logs cleared. Future reports will only include new activity.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                Spacer(Modifier.height(24.dp))
            }
        }
    }

    // Restore confirmation dialog
    if (showRestoreDialog) {
        AlertDialog(
            onDismissRequest = { showRestoreDialog = false },
            title = { Text("Restore Backup") },
            text = {
                Text(
                    "This will permanently replace all current data with the contents of the selected backup. This cannot be undone."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showRestoreDialog = false
                        restoreLauncher.launch(arrayOf("application/zip", "application/octet-stream"))
                    }
                ) {
                    Text("Choose Backup File", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showRestoreDialog = false }) { Text("Cancel") }
            }
        )
    }

    // Clear logs confirmation dialog
    if (showClearLogsDialog) {
        AlertDialog(
            onDismissRequest = { showClearLogsDialog = false },
            title = { Text("Clear Logs") },
            text = {
                Text("This removes all stored log history. Future bug reports will only include activity after this point.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.clearAllLogs()
                        showClearLogsDialog = false
                        logsCleared = true
                    }
                ) { Text("Clear", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showClearLogsDialog = false }) { Text("Cancel") }
            }
        )
    }
}
