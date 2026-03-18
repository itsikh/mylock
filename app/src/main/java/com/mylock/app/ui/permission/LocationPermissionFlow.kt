package com.mylock.app.ui.permission

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.PermissionChecker

/**
 * Drives the two-step Android location permission flow required for geofencing:
 *
 * Step 1 — Foreground location (ACCESS_FINE_LOCATION)
 *   Required for any location use. Android blocks background location until this is granted.
 *
 * Step 2 — Background location (ACCESS_BACKGROUND_LOCATION, Android 10+)
 *   Required so the OS can deliver geofence events when the app is not in the foreground.
 *   Android forces this to be a separate request so the user sees a distinct rationale.
 *
 * Step 3 — Notifications (POST_NOTIFICATIONS, Android 13+)
 *   Optional but needed for the backup-ready notification. Requested last, after location.
 *
 * If all permissions are already granted, [onAllGranted] is called immediately on first
 * composition without showing any UI.
 */
@Composable
fun LocationPermissionFlow(
    onAllGranted: @Composable () -> Unit
) {
    val context = LocalContext.current

    fun hasPermission(p: String) =
        ContextCompat.checkSelfPermission(context, p) == PermissionChecker.PERMISSION_GRANTED

    val hasFine = hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    val hasBg = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        hasPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) else true
    val hasNotif = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
        hasPermission(Manifest.permission.POST_NOTIFICATIONS) else true

    // Mutable so re-composition triggers after each grant
    var fineGranted by remember { mutableStateOf(hasFine) }
    var bgGranted by remember { mutableStateOf(hasBg) }
    var notifGranted by remember { mutableStateOf(hasNotif) }

    // Launcher 1: foreground location
    val fineLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        fineGranted = results[Manifest.permission.ACCESS_FINE_LOCATION] == true
    }

    // Launcher 2: background location (separate request — Android requirement)
    val bgLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        bgGranted = granted
    }

    // Launcher 3: notifications
    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        notifGranted = granted
    }

    when {
        fineGranted && bgGranted && notifGranted -> onAllGranted()

        !fineGranted -> PermissionRationaleScreen(
            title = "Location Access",
            body = "MyLock needs your location to detect when you're near home so the unlock button activates automatically.",
            buttonLabel = "Grant Location",
            onGrant = {
                fineLauncher.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    )
                )
            },
            onSkip = { fineGranted = true }  // allow skipping (geofence won't work but app still usable)
        )

        !bgGranted -> PermissionRationaleScreen(
            title = "Background Location",
            body = "To detect when you arrive home even when the app is closed, MyLock needs background location access.\n\nOn the next screen, choose \"Allow all the time\".",
            buttonLabel = "Continue",
            onGrant = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    bgLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                } else {
                    bgGranted = true
                }
            },
            onSkip = { bgGranted = true }
        )

        !notifGranted -> PermissionRationaleScreen(
            title = "Notifications",
            body = "Allow notifications so MyLock can alert you when a backup is ready to save.",
            buttonLabel = "Allow Notifications",
            onGrant = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    notifGranted = true
                }
            },
            onSkip = { notifGranted = true }
        )
    }
}

@Composable
private fun PermissionRationaleScreen(
    title: String,
    body: String,
    buttonLabel: String,
    onGrant: () -> Unit,
    onSkip: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Default.LocationOn,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(24.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(32.dp))
        Button(onClick = onGrant) {
            Text(buttonLabel)
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onSkip) {
            Text("Skip for now")
        }
    }
}
