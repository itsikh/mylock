package com.mylock.app.ui.screens.bugreport

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BugReportScreen(
    mode: ReportMode = ReportMode.BUG_REPORT,
    onBack: () -> Unit = {},
    viewModel: BugReportViewModel = hiltViewModel()
) {
    LaunchedEffect(mode) {
        viewModel.setReportMode(mode)
    }

    val subject by viewModel.subject.collectAsState()
    val description by viewModel.description.collectAsState()
    val autofix by viewModel.autofix.collectAsState()
    val submitting by viewModel.submitting.collectAsState()
    val result by viewModel.result.collectAsState()
    val screenshotUri by viewModel.screenshotUri.collectAsState()
    val reportMode by viewModel.reportMode.collectAsState()

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        viewModel.setScreenshot(uri)
    }

    val screenTitle = when (reportMode) {
        ReportMode.BUG_REPORT -> "Report a Bug"
        ReportMode.USER_FEEDBACK -> "Feedback & Suggestions"
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(screenTitle) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            val subjectLabel = when (reportMode) {
                ReportMode.BUG_REPORT -> "Bug subject *"
                ReportMode.USER_FEEDBACK -> "Subject *"
            }

            val descriptionLabel = when (reportMode) {
                ReportMode.BUG_REPORT -> "Describe the issue *"
                ReportMode.USER_FEEDBACK -> "Describe your feedback or suggestion *"
            }

            OutlinedTextField(
                value = subject,
                onValueChange = { viewModel.subject.value = it },
                label = { Text(subjectLabel) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = description,
                onValueChange = { viewModel.description.value = it },
                label = { Text(descriptionLabel) },
                minLines = 4,
                maxLines = 8,
                modifier = Modifier.fillMaxWidth()
            )

            // Screenshot attachment
            if (screenshotUri != null) {
                Box(modifier = Modifier.fillMaxWidth()) {
                    val context = LocalContext.current
                    val bitmap = remember(screenshotUri) {
                        screenshotUri?.let { uri ->
                            try {
                                context.contentResolver.openInputStream(uri)?.use { stream ->
                                    BitmapFactory.decodeStream(stream)
                                }
                            } catch (e: Exception) {
                                null
                            }
                        }
                    }
                    bitmap?.let {
                        Image(
                            bitmap = it.asImageBitmap(),
                            contentDescription = "Screenshot",
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(200.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp)),
                            contentScale = ContentScale.Fit
                        )
                    }
                    IconButton(
                        onClick = { viewModel.setScreenshot(null) },
                        modifier = Modifier.align(Alignment.TopEnd)
                    ) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = "Remove screenshot",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            } else {
                OutlinedButton(
                    onClick = { imagePickerLauncher.launch("image/*") },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        Icons.Filled.AddPhotoAlternate,
                        contentDescription = null,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                    Text("Attach screenshot")
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Device info summary
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = "Information included in the report",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = viewModel.deviceInfo,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Log lines: ${viewModel.logLineCount}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (screenshotUri != null || viewModel.hasAutoScreenshot) {
                        Text(
                            text = if (viewModel.hasAutoScreenshot && screenshotUri == null)
                                "Screenshot: auto-captured"
                            else
                                "Screenshot: attached",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    if (viewModel.hasCrashLogs) {
                        Text(
                            text = "Crash log: attached",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            // Auto-fix toggle (only for bug reports)
            if (reportMode == ReportMode.BUG_REPORT) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Auto-fix",
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Text(
                            text = if (autofix) "The bug will be fixed automatically" else "The bug will wait for manual review",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = autofix,
                        onCheckedChange = { viewModel.autofix.value = it }
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))
            }

            Button(
                onClick = { viewModel.submitReport() },
                enabled = subject.isNotBlank() && description.isNotBlank() && !submitting,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (submitting) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .padding(end = 8.dp)
                            .height(16.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp
                    )
                }
                Text("Submit Report")
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }

    // Result dialog
    result?.let { res ->
        AlertDialog(
            onDismissRequest = { viewModel.clearResult() },
            title = {
                Text(if (res.success) "Report submitted successfully!" else "Failed to submit report")
            },
            text = {
                if (res.success && res.issueUrl != null) {
                    Text("The report was created successfully on GitHub.")
                } else if (res.error != null) {
                    Text(res.error)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (res.success) {
                        viewModel.clearResult()
                        onBack()
                    } else {
                        viewModel.clearResult()
                    }
                }) {
                    Text(if (res.success) "Close" else "Try Again")
                }
            }
        )
    }
}
