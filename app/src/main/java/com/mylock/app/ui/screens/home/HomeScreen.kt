package com.mylock.app.ui.screens.home

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.LocationOff
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.WifiTethering
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import com.mylock.app.security.BiometricHelper
import com.mylock.app.R
import com.mylock.app.data.LockEvent
import com.mylock.app.data.LockEventType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenSettings: () -> Unit = {},
    viewModel: HomeViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val events by viewModel.recentEvents.collectAsState()
    val adminMode by viewModel.adminMode.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val activity = LocalContext.current as FragmentActivity

    LaunchedEffect(state.errorMessage) {
        state.errorMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    LaunchedEffect(Unit) { viewModel.refresh() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (!state.isConfigured) {
                SetupPrompt(onOpenSettings)
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Spacer(Modifier.height(16.dp))

                    Text(
                        text = state.lockName.ifEmpty { "My Lock" },
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(Modifier.height(8.dp))

                    ProximityBadge(isNearHome = state.isNearHome)

                    Spacer(Modifier.height(48.dp))

                    UnlockButton(
                        isLoading = state.isLoading,
                        isNearHome = state.isNearHome,
                        lastActionSuccess = state.lastActionSuccess,
                        onClick = { viewModel.handleUnlockTap() }
                    )

                    Spacer(Modifier.height(32.dp))

                    if (adminMode) {
                        RemoteAccessSection(
                            isLoading = state.isLoading,
                            onRemoteUnlock = {
                                BiometricHelper.authenticate(
                                    activity = activity,
                                    title = "Remote Unlock",
                                    subtitle = "Authenticate to unlock from anywhere",
                                    onSuccess = { viewModel.remoteUnlock() },
                                    onError = { msg ->
                                        viewModel.showError(msg)
                                    }
                                )
                            }
                        )
                        Spacer(Modifier.height(24.dp))
                    }

                    if (events.isNotEmpty()) {
                        Text(
                            text = "Recent Activity",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 8.dp)
                        )
                        LazyColumn(
                            contentPadding = PaddingValues(bottom = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(events.take(10)) { event ->
                                LockEventRow(event)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SetupPrompt(onNavigateToSettings: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Default.Lock,
            contentDescription = null,
            modifier = Modifier.size(72.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(16.dp))
        Text("Setup required", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Configure your TTLock account and select your lock in Settings",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 32.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onNavigateToSettings) { Text("Open Settings") }
    }
}

@Composable
private fun ProximityBadge(isNearHome: Boolean) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(
            imageVector = if (isNearHome) Icons.Default.LocationOn else Icons.Default.LocationOff,
            contentDescription = null,
            tint = if (isNearHome) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp)
        )
        Text(
            text = if (isNearHome) "Near home" else "Away from home",
            style = MaterialTheme.typography.labelMedium,
            color = if (isNearHome) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun UnlockButton(
    isLoading: Boolean,
    isNearHome: Boolean,
    lastActionSuccess: Boolean?,
    onClick: () -> Unit
) {
    val bgColor by animateColorAsState(
        targetValue = when {
            lastActionSuccess == true -> Color(0xFF4CAF50)
            lastActionSuccess == false -> MaterialTheme.colorScheme.error
            !isNearHome -> MaterialTheme.colorScheme.surfaceVariant
            else -> MaterialTheme.colorScheme.primary
        },
        animationSpec = tween(400),
        label = "unlockBtnColor"
    )

    Button(
        onClick = onClick,
        enabled = !isLoading,
        modifier = Modifier.size(140.dp),
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(containerColor = bgColor),
        contentPadding = PaddingValues(0.dp)
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                color = Color.White,
                modifier = Modifier.size(32.dp),
                strokeWidth = 3.dp
            )
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = when (lastActionSuccess) {
                        true -> Icons.Default.Check
                        false -> Icons.Default.Close
                        null -> if (isNearHome) Icons.Default.LockOpen else Icons.Default.MyLocation
                    },
                    contentDescription = "Unlock",
                    modifier = Modifier.size(40.dp),
                    tint = Color.White
                )
                Text(
                    text = if (!isNearHome) "Check" else "Unlock",
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }
    }
}

@Composable
private fun RemoteAccessSection(
    isLoading: Boolean,
    onRemoteUnlock: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    Icons.Default.WifiTethering,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    "Remote Access",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
            Text(
                "Unlock from anywhere — bypasses proximity check",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.2f))
            OutlinedButton(
                onClick = onRemoteUnlock,
                enabled = !isLoading,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                )
            ) {
                Icon(Icons.Default.LockOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text("Remote Unlock")
            }
        }
    }
}

@Composable
private fun LockEventRow(event: LockEvent) {
    val fmt = remember { SimpleDateFormat("HH:mm  dd/MM", Locale.getDefault()) }
    val isSuccess = event.eventType == LockEventType.UNLOCK || event.eventType == LockEventType.LOCK
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = when (event.eventType) {
                    LockEventType.UNLOCK -> Icons.Default.LockOpen
                    LockEventType.LOCK -> Icons.Default.Lock
                    else -> Icons.Default.Close
                },
                contentDescription = null,
                tint = if (isSuccess) Color(0xFF4CAF50) else MaterialTheme.colorScheme.error,
                modifier = Modifier.size(20.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = when (event.eventType) {
                        LockEventType.UNLOCK -> "Unlocked"
                        LockEventType.LOCK -> "Locked"
                        LockEventType.UNLOCK_FAILED -> "Unlock failed"
                        LockEventType.LOCK_FAILED -> "Lock failed"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                event.errorMessage?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
            Text(
                text = fmt.format(Date(event.timestamp)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
