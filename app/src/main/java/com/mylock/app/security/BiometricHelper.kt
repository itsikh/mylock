package com.mylock.app.security

import androidx.biometric.BiometricManager.Authenticators
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/**
 * Reusable wrapper around [BiometricPrompt] for biometric and device-credential authentication.
 *
 * Accepts both strong biometrics (fingerprint, face) and the device PIN/pattern/password as
 * fallback by passing `BIOMETRIC_STRONG or DEVICE_CREDENTIAL` to [BiometricPrompt.PromptInfo].
 * This means the prompt will always have a way to authenticate even on devices without biometrics.
 *
 * ## Why FragmentActivity is required
 * [BiometricPrompt] internally uses a Fragment to host the system bottom sheet, so it needs
 * a [FragmentActivity] — not just any [Context]. This is why [MainActivity] extends
 * [FragmentActivity] instead of the more common [ComponentActivity].
 *
 * ## Usage
 * ```kotlin
 * BiometricHelper.authenticate(
 *     activity = activity,
 *     title = "Confirm identity",
 *     subtitle = "Authenticate to continue",
 *     onSuccess = { performSensitiveAction() },
 *     onError = { errorMsg -> showToast(errorMsg) }
 * )
 * ```
 *
 * ## Integration
 * Called by [ClearDataConfirmationDialog] before permanently deleting user data.
 * Apps can call it directly from any screen that needs a security gate.
 */
object BiometricHelper {

    /**
     * Displays the system biometric/credential authentication prompt.
     *
     * The prompt is shown asynchronously. Callbacks are delivered on the main thread.
     * If the user fails biometric recognition, the prompt automatically stays open for
     * retry — [onError] is only called when the user actively cancels or an unrecoverable
     * error occurs.
     *
     * @param activity The [FragmentActivity] that hosts the biometric prompt fragment.
     * @param title Primary text shown in the prompt dialog (e.g. "Confirm identity").
     * @param subtitle Secondary text shown below the title (describes what will happen).
     * @param onSuccess Invoked on the main thread when authentication succeeds.
     * @param onError Invoked on the main thread when the user cancels or an error occurs,
     *                with a localized human-readable error message.
     */
    fun authenticate(
        activity: FragmentActivity,
        title: String,
        subtitle: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        val executor = ContextCompat.getMainExecutor(activity)

        val biometricPrompt = BiometricPrompt(
            activity,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    onSuccess()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    onError(errString.toString())
                }

                override fun onAuthenticationFailed() {
                    // Biometric not recognized — prompt stays open for retry automatically
                }
            }
        )

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setAllowedAuthenticators(Authenticators.BIOMETRIC_STRONG or Authenticators.DEVICE_CREDENTIAL)
            .build()

        biometricPrompt.authenticate(promptInfo)
    }
}
