package com.template.app.security

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.fragment.app.FragmentActivity

/**
 * Composable confirmation dialog for destructive data-deletion actions, with a biometric
 * authentication gate before the deletion is executed.
 *
 * ## Flow
 * 1. Dialog is shown describing what will be deleted and what will be preserved.
 * 2. User taps "Delete".
 * 3. [BiometricHelper.authenticate] is called — the system biometric/PIN prompt appears.
 * 4. On successful authentication, [onConfirmed] is invoked to perform the actual deletion.
 * 5. If the user cancels auth or auth fails, nothing is deleted.
 *
 * ## Why biometric gating?
 * A "Delete All Data" action is irreversible. Requiring device authentication prevents
 * accidental taps and unauthorized access on an unlocked device.
 *
 * ## Usage
 * ```kotlin
 * var showDialog by remember { mutableStateOf(false) }
 * if (showDialog) {
 *     ClearDataConfirmationDialog(
 *         onDismiss = { showDialog = false },
 *         onConfirmed = { viewModel.clearAllData() },
 *         deletedDescription = "all conversation history",
 *         preservedDescription = "API keys and settings"
 *     )
 * }
 * ```
 *
 * **Requirement**: Must be called from within a composable hosted in a [FragmentActivity]
 * (i.e. [MainActivity]), because [BiometricHelper] requires a [FragmentActivity] context.
 *
 * @param onDismiss Called when the dialog is dismissed (Cancel button or back gesture).
 * @param onConfirmed Called only after the user successfully authenticates. Perform deletion here.
 * @param title Dialog title. Defaults to "Delete All Data".
 * @param deletedDescription What will be permanently deleted (shown in the message body).
 * @param preservedDescription What will NOT be deleted (shown in the message body for reassurance).
 */
@Composable
fun ClearDataConfirmationDialog(
    onDismiss: () -> Unit,
    onConfirmed: () -> Unit,
    title: String = "Delete All Data",
    deletedDescription: String = "all user data",
    preservedDescription: String = "API keys and settings"
) {
    val context = LocalContext.current
    val activity = context as FragmentActivity

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Text(
                "This will permanently delete $deletedDescription. " +
                    "$preservedDescription will be preserved. " +
                    "This action cannot be undone!"
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onDismiss()
                    BiometricHelper.authenticate(
                        activity = activity,
                        title = "Authentication Required",
                        subtitle = "Authenticate to delete all data",
                        onSuccess = onConfirmed,
                        onError = { /* User cancelled or auth failed — do nothing */ }
                    )
                }
            ) {
                Text("Delete", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
