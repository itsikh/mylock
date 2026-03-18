package com.mylock.app.bugreport

import android.os.Build
import com.mylock.app.BuildConfig
import com.mylock.app.logging.AppLogger
import com.mylock.app.logging.CrashLogRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Automatically files a GitHub issue on the first app launch after a crash.
 *
 * ## How it works
 * 1. [GlobalExceptionHandler] writes a crash log file to disk before the app dies.
 * 2. On next launch, [MainActivity] calls [checkAndReport] (via `lifecycleScope.launch`).
 * 3. If a crash log exists, this class builds a structured GitHub issue and submits it
 *    via [GitHubIssuesClient], then **clears the crash log** so it is only reported once.
 * 4. If submission fails (e.g. no internet, no GitHub token), the log is left on disk
 *    and will be retried on the next launch.
 *
 * ## Issue format
 * - Title: `[Auto-crash] {ExceptionType}` (max 120 chars)
 * - Labels: `bug`, `from-app`, `autofix`, `crash`
 * - Body: device info + crash log (first 3000 chars) + recent AppLogger entries
 *
 * ## Requirements
 * - A GitHub PAT must be stored in [SecureKeyManager] under [GitHubIssuesClient.KEY_GITHUB_TOKEN].
 *   Without a token, [GitHubIssuesClient.createIssue] returns an error and the log is preserved.
 * - INTERNET permission must be declared in AndroidManifest.xml (already present in the template).
 *
 * ## Wiring
 * Called from [MainActivity.onCreate] via `lifecycleScope.launch { crashAutoReporter.checkAndReport() }`.
 * This runs on the default dispatcher (main), but [GitHubIssuesClient.createIssue] internally
 * switches to [Dispatchers.IO] for the network call, so no extra dispatcher switching is needed here.
 */
@Singleton
class CrashAutoReporter @Inject constructor(
    private val crashLogRepository: CrashLogRepository,
    private val gitHubIssuesClient: GitHubIssuesClient
) {
    /**
     * Checks for a pending crash log and, if found, submits it as a GitHub issue.
     *
     * This is a suspend function — call it from a coroutine scope (e.g. `lifecycleScope.launch`).
     * It is safe to call on every launch; it no-ops when no crash logs exist.
     */
    suspend fun checkAndReport() {
        if (!crashLogRepository.hasCrashLogs()) return
        val crashLog = crashLogRepository.getLatestCrashLog() ?: return

        AppLogger.i(TAG, "Crash log detected — filing auto crash report")

        val exceptionType = crashLog.lines()
            .firstOrNull { it.startsWith("Exception:") }
            ?.removePrefix("Exception:")?.trim()
            ?: "Unknown exception"

        val title = "[Auto-crash] $exceptionType".take(120)

        val deviceInfo = buildString {
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            append("App: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        }

        val body = buildString {
            appendLine("## Auto-reported Crash")
            appendLine("This issue was automatically filed on app restart after a crash was detected.")
            appendLine()
            appendLine("## Device Info")
            appendLine("```")
            appendLine(deviceInfo)
            appendLine("```")
            appendLine()
            appendLine("## Crash Log")
            appendLine("<details>")
            appendLine("<summary>Show crash log</summary>")
            appendLine()
            appendLine("```")
            appendLine(crashLog.take(3000))
            appendLine("```")
            appendLine("</details>")
            appendLine()
            appendLine("## App Logs (last 100 lines)")
            appendLine("<details>")
            appendLine("<summary>Show logs</summary>")
            appendLine()
            appendLine("```")
            appendLine(AppLogger.getRecentLogs(100).take(5000))
            appendLine("```")
            appendLine("</details>")
        }

        val result = gitHubIssuesClient.createIssue(
            title  = title,
            body   = body,
            labels = listOf("bug", "from-app", "autofix", "crash")
        )

        if (result.success) {
            AppLogger.i(TAG, "Auto crash report submitted: ${result.issueUrl}")
            crashLogRepository.clearCrashLogs()
        } else {
            // Leave crash log on disk — it will be retried on next launch
            AppLogger.w(TAG, "Auto crash report failed (will retry next launch): ${result.error}")
        }
    }

    companion object {
        private const val TAG = "CrashAutoReporter"
    }
}
