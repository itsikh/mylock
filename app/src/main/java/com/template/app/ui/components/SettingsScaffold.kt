package com.template.app.ui.components

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.template.app.logging.LogLevel

/**
 * Reusable settings screen scaffold providing admin mode infrastructure and an About section.
 *
 * Renders a [Column] with three regions:
 * 1. **App content** — the [content] slot, rendered first. Put app-specific settings here
 *    (e.g. API key fields, theme toggles, notification preferences).
 * 2. **Debug section** — admin-only; only visible when [adminMode] is `true`. Contains:
 *    - Detailed logging toggle (maps to [logging.DebugSettings.setLogLevel])
 *    - Bug report button visibility toggle (maps to [logging.DebugSettings.setShowBugButton])
 * 3. **About section** — always visible. Shows [appName] and [versionName]. Tapping the
 *    version string 7 times activates/deactivates admin mode via [AdminModeHelper].
 *
 * ## Admin mode activation
 * Admin mode is toggled by tapping the version string [AdminModeHelper.REQUIRED_CLICKS]
 * (7) times. A [Toast] is shown to confirm activation or deactivation. The state is
 * persisted across sessions via [logging.DebugSettings.setAdminMode].
 *
 * ## Usage
 * ```kotlin
 * SettingsScaffold(
 *     appName = AppConfig.APP_NAME,
 *     versionName = BuildConfig.VERSION_NAME,
 *     adminMode = adminMode,
 *     logLevel = logLevel,
 *     showBugButton = showBugButton,
 *     onAdminModeToggle = { viewModel.setAdminMode(it) },
 *     onDetailedLoggingToggle = { viewModel.setDetailedLogging(it) },
 *     onShowBugButtonToggle = { viewModel.setShowBugButton(it) }
 * ) {
 *     // App-specific settings content here
 *     ApiKeySection(...)
 * }
 * ```
 *
 * @param appName Display name shown in the About card (typically [AppConfig.APP_NAME]).
 * @param versionName Version string shown in the About card (typically [BuildConfig.VERSION_NAME]).
 * @param adminMode Whether admin/developer mode is currently active.
 * @param logLevel Current [LogLevel] from [logging.DebugSettings.logLevel], used by the logging toggle.
 * @param showBugButton Current bug button visibility from [logging.DebugSettings.showBugButton].
 * @param onAdminModeToggle Called when admin mode is toggled (after the 7-tap sequence).
 *                         Persist via [logging.DebugSettings.setAdminMode].
 * @param onDetailedLoggingToggle Called when the detailed logging switch changes.
 *                                `true` → [LogLevel.DEBUG], `false` → default level.
 * @param onShowBugButtonToggle Called when the bug button visibility switch changes.
 * @param content App-specific settings content rendered above the admin section.
 */
@Composable
fun SettingsScaffold(
    appName: String,
    versionName: String,
    adminMode: Boolean,
    logLevel: LogLevel,
    showBugButton: Boolean,
    onAdminModeToggle: (Boolean) -> Unit,
    onDetailedLoggingToggle: (Boolean) -> Unit,
    onShowBugButtonToggle: (Boolean) -> Unit,
    content: @Composable () -> Unit = {}
) {
    val context = LocalContext.current
    val adminModeHelper = rememberAdminModeHelper { enabled ->
        onAdminModeToggle(enabled)
        val message = if (enabled) "Admin Mode Enabled" else "Admin Mode Disabled"
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    Column {
        // App-specific settings content
        content()

        // Debug Section (Admin Mode Only)
        AnimatedVisibility(visible = adminMode) {
            Column {
                Spacer(modifier = Modifier.height(24.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(16.dp))

                SectionHeader("Debug")
                Spacer(modifier = Modifier.height(8.dp))

                Card(modifier = Modifier.fillMaxWidth()) {
                    Column {
                        // Detailed logging toggle
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Detailed Logging",
                                    style = MaterialTheme.typography.bodyLarge
                                )
                                Text(
                                    text = "Current level: ${logLevel.name}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = logLevel == LogLevel.DEBUG,
                                onCheckedChange = { onDetailedLoggingToggle(it) }
                            )
                        }

                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

                        // Bug button visibility toggle
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Bug Report Button",
                                    style = MaterialTheme.typography.bodyLarge
                                )
                                Text(
                                    text = "Show floating button for quick bug reports",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = showBugButton,
                                onCheckedChange = { onShowBugButtonToggle(it) }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(16.dp))
            }
        }

        // About Section
        SectionHeader("About")
        Spacer(modifier = Modifier.height(8.dp))

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = appName,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Version $versionName${if (adminMode) " (Admin Mode)" else ""}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.clickable {
                        adminModeHelper.onVersionClick(adminMode)
                    }
                )
            }
        }
    }
}

/**
 * Styled section header text used to group related settings rows.
 *
 * Renders [title] in [MaterialTheme.typography.titleMedium] with the primary color.
 * Use above a [Card] or group of settings rows to label the section.
 */
@Composable
fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary
    )
}
