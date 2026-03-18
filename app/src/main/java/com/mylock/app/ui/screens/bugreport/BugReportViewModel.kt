package com.mylock.app.ui.screens.bugreport

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mylock.app.BuildConfig
import com.mylock.app.bugreport.GitHubIssueResult
import com.mylock.app.bugreport.GitHubIssuesClient
import com.mylock.app.bugreport.ScreenshotHolder
import com.mylock.app.logging.AppLogger
import com.mylock.app.logging.CrashLogRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import javax.inject.Inject

/**
 * Controls the mode of the bug report screen, determining labels and UI copy.
 *
 * | Mode | GitHub labels | Use case |
 * |------|--------------|---------|
 * | [BUG_REPORT] | `bug`, `from-app`, optionally `autofix` | User found something broken |
 * | [USER_FEEDBACK] | `user`, `from-app`, `enhancement` | User has a suggestion or comment |
 */
enum class ReportMode {
    BUG_REPORT,
    USER_FEEDBACK
}

/**
 * ViewModel for [BugReportScreen].
 *
 * Manages all state for the bug report / user feedback form and orchestrates the
 * multi-step submission process:
 * 1. Optionally compress and upload a screenshot via [GitHubIssuesClient.uploadScreenshot].
 * 2. Build a structured Markdown issue body including device info, crash log, and recent logs.
 * 3. Submit the issue via [GitHubIssuesClient.createIssue].
 *
 * ## State
 * All mutable UI state is exposed as [MutableStateFlow] so [BugReportScreen] can bind
 * to it reactively. The screen writes directly to [subject], [description], and [autofix]
 * (public `MutableStateFlow`s) while read-only state like [submitting] and [result] is
 * exposed only through their private backing flows.
 *
 * ## Auto-attached data
 * Every submission automatically includes:
 * - [deviceInfo] — device model, Android version, and app version/build.
 * - Recent [AppLogger] entries (last 100 lines, capped at 5000 chars).
 * - The latest crash log from [CrashLogRepository], if one exists (capped at 3000 chars).
 * - Screenshot upload URL, if the user attached an image.
 *
 * ## Report modes
 * [setReportMode] switches between [ReportMode.BUG_REPORT] and [ReportMode.USER_FEEDBACK].
 * Switching to [USER_FEEDBACK] also sets [autofix] to `false` (the auto-fix option only
 * applies to bug reports).
 */
@HiltViewModel
class BugReportViewModel @Inject constructor(
    private val gitHubIssuesClient: GitHubIssuesClient,
    private val crashLogRepository: CrashLogRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {

    /** Issue title as entered by the user. Write directly: `viewModel.subject.value = it`. */
    val subject = MutableStateFlow("")

    /** Issue description body as entered by the user. Write directly: `viewModel.description.value = it`. */
    val description = MutableStateFlow("")

    /**
     * Whether the `autofix` label should be added to the created issue.
     * When `true`, the issue is tagged for automated fix workflows.
     * Only relevant for [ReportMode.BUG_REPORT]; forced to `false` for [ReportMode.USER_FEEDBACK].
     */
    val autofix = MutableStateFlow(true)

    private val _reportMode = MutableStateFlow(ReportMode.BUG_REPORT)
    /** The active report mode. Read-only; change via [setReportMode]. */
    val reportMode: StateFlow<ReportMode> = _reportMode

    private val _submitting = MutableStateFlow(false)
    /** `true` while a submission is in progress. Use to show a loading indicator and disable the submit button. */
    val submitting: StateFlow<Boolean> = _submitting

    private val _result = MutableStateFlow<GitHubIssueResult?>(null)
    /** The result of the most recent submission, or `null` if not yet submitted or cleared via [clearResult]. */
    val result: StateFlow<GitHubIssueResult?> = _result

    private val _screenshotUri = MutableStateFlow<Uri?>(null)
    /** URI of the attached screenshot image, or `null` if none selected. */
    val screenshotUri: StateFlow<Uri?> = _screenshotUri

    /**
     * Pre-compressed JPEG bytes captured automatically by [ui.components.FloatingBugButton].
     * Set during [init] by consuming [ScreenshotHolder]. Cleared when the user attaches
     * their own image via [setScreenshot].
     */
    private var capturedScreenshotBytes: ByteArray? = ScreenshotHolder.take()

    /**
     * `true` if a screenshot was auto-captured by the floating bug button and has not yet
     * been replaced by a user-selected image. Shown in the report info card.
     */
    val hasAutoScreenshot: Boolean get() = capturedScreenshotBytes != null

    /**
     * Pre-built device information string included in every report.
     * Format:
     * ```
     * Device: {Manufacturer} {Model}
     * Android: {Release} (API {SDK})
     * App: {versionName} ({versionCode})
     * ```
     */
    val deviceInfo: String = buildString {
        appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLine("App: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
    }

    /** `true` if crash log files exist in [CrashLogRepository]. Evaluated once at construction. */
    val hasCrashLogs: Boolean = crashLogRepository.hasCrashLogs()

    /** Total number of log lines currently in [AppLogger]'s buffer. Shown in the report info card. */
    val logLineCount: Int = AppLogger.exportLogs().lines().size

    /**
     * Switches the active report mode. When switching to [ReportMode.USER_FEEDBACK],
     * [autofix] is automatically set to `false`.
     */
    fun setReportMode(mode: ReportMode) {
        _reportMode.value = mode
        if (mode == ReportMode.USER_FEEDBACK) {
            autofix.value = false
        }
    }

    /**
     * Sets the screenshot to attach to the report.
     *
     * @param uri A content URI from the image picker, or `null` to remove the screenshot.
     */
    fun setScreenshot(uri: Uri?) {
        _screenshotUri.value = uri
        if (uri != null) capturedScreenshotBytes = null // user chose their own; discard auto-capture
        AppLogger.d(TAG, "Screenshot ${if (uri != null) "attached" else "removed"}")
    }

    /**
     * Submits the bug report or feedback to GitHub Issues.
     *
     * No-op if [subject] or [description] is blank. Sets [submitting] to `true` for the
     * duration and populates [result] when done. The submission runs on [viewModelScope]
     * and is cancelled if the ViewModel is cleared (e.g. back navigation during submission).
     *
     * ## Submission steps
     * 1. If [screenshotUri] is set, compress it via [compressScreenshot] and upload via
     *    [GitHubIssuesClient.uploadScreenshot]. Screenshot failure is non-fatal.
     * 2. Build the Markdown issue body with device info, screenshot, crash log, and logs.
     * 3. Compute labels based on [reportMode] and [autofix].
     * 4. Call [GitHubIssuesClient.createIssue] and store the result in [result].
     */
    fun submitReport() {
        if (subject.value.isBlank() || description.value.isBlank()) return

        viewModelScope.launch {
            try {
                _submitting.value = true
                _result.value = null

                val recentLogs = AppLogger.getRecentLogs(100)
                val crashLog = if (hasCrashLogs) crashLogRepository.getLatestCrashLog() else null

                var screenshotUrl: String? = null
                val screenshotBytes = _screenshotUri.value?.let { uri ->
                    compressScreenshot(uri)
                } ?: capturedScreenshotBytes
                if (screenshotBytes != null) {
                    AppLogger.d(TAG, "Uploading screenshot...")
                    screenshotUrl = gitHubIssuesClient.uploadScreenshot(screenshotBytes)
                }

                val body = buildString {
                    appendLine("## Description")
                    appendLine(description.value)
                    appendLine()

                    if (screenshotUrl != null) {
                        appendLine("## Screenshot")
                        appendLine("![screenshot]($screenshotUrl)")
                        appendLine()
                    }

                    appendLine("## Device Info")
                    appendLine("```")
                    append(deviceInfo)
                    appendLine("```")
                    appendLine()

                    if (crashLog != null) {
                        appendLine("## Latest Crash Log")
                        appendLine("<details>")
                        appendLine("<summary>Show crash log</summary>")
                        appendLine()
                        appendLine("```")
                        appendLine(crashLog.take(3000))
                        appendLine("```")
                        appendLine("</details>")
                        appendLine()
                    }

                    appendLine("## Logs (last 100 lines)")
                    appendLine("<details>")
                    appendLine("<summary>Show logs</summary>")
                    appendLine()
                    appendLine("```")
                    appendLine(recentLogs.take(5000))
                    appendLine("```")
                    appendLine("</details>")
                }

                val labels = when (_reportMode.value) {
                    ReportMode.BUG_REPORT -> {
                        val list = mutableListOf("bug", "from-app")
                        if (autofix.value) {
                            list.add("autofix")
                        }
                        list
                    }
                    ReportMode.USER_FEEDBACK -> {
                        mutableListOf("user", "from-app", "enhancement")
                    }
                }

                val result = gitHubIssuesClient.createIssue(
                    title = subject.value.trim(),
                    body = body,
                    labels = labels
                )
                _result.value = result

                if (result.success) {
                    AppLogger.i(TAG, "Bug report submitted successfully")
                }
            } catch (e: Exception) {
                _result.value = GitHubIssueResult(success = false, error = "Error: ${e.message}")
                AppLogger.e(TAG, "Failed to submit bug report", e)
            } finally {
                _submitting.value = false
            }
        }
    }

    /**
     * Reads the image at [uri] via [ContentResolver], scales it down to fit within 1280×1280
     * if necessary (preserving aspect ratio), and compresses it as JPEG at 70% quality.
     *
     * Compression keeps screenshot uploads small (typically < 200 KB) so they don't time
     * out on the GitHub Contents API's 15-second read timeout.
     *
     * @return JPEG-compressed bytes, or `null` on any I/O or decoding error.
     */
    private fun compressScreenshot(uri: Uri): ByteArray? {
        return try {
            val inputStream = context.contentResolver.openInputStream(uri) ?: return null
            val bitmap = BitmapFactory.decodeStream(inputStream)
            inputStream.close()
            val maxDim = 1280
            val scaled = if (bitmap.width > maxDim || bitmap.height > maxDim) {
                val ratio = minOf(maxDim.toFloat() / bitmap.width, maxDim.toFloat() / bitmap.height)
                Bitmap.createScaledBitmap(bitmap, (bitmap.width * ratio).toInt(), (bitmap.height * ratio).toInt(), true)
            } else {
                bitmap
            }
            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 70, out)
            out.toByteArray()
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to compress screenshot", e)
            null
        }
    }

    /** Clears [result] so the result dialog is dismissed without navigating away. */
    fun clearResult() {
        _result.value = null
    }

    companion object {
        private const val TAG = "BugReportViewModel"
    }
}
