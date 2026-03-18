package com.mylock.app.logging

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Uncaught exception handler that captures crashes to disk before the app dies.
 *
 * Installed in [TemplateApplication.onCreate] as the default handler via
 * `Thread.setDefaultUncaughtExceptionHandler`. When any thread throws an unhandled
 * exception, this handler:
 *
 * 1. Logs the crash via [AppLogger.e] (adds it to the in-memory buffer).
 * 2. Writes a structured crash report file to `filesDir/crash_logs/crash_<timestamp>.txt`.
 *    The report includes the exception class, message, full stack trace, and the last
 *    200 [AppLogger] entries so there is context leading up to the crash.
 * 3. Delegates to the original system handler so the OS can show the "App stopped" dialog
 *    and perform its own crash handling.
 *
 * ## Reading crash logs
 * Use [CrashLogRepository] to check for, read, and clear crash log files.
 * [BugReportViewModel] automatically attaches the latest crash log to bug reports.
 *
 * ## Crash log directory
 * Files are stored in `context.filesDir/crash_logs/` (internal storage, not cleared by the OS).
 * The filename pattern is `crash_yyyy-MM-dd_HH-mm-ss.txt`.
 *
 * @param context Application context used to locate the crash log directory.
 * @param defaultHandler The previously installed handler (typically the system handler).
 *                       It is always called after saving the crash file.
 */
class GlobalExceptionHandler(
    private val context: Context,
    private val defaultHandler: Thread.UncaughtExceptionHandler?
) : Thread.UncaughtExceptionHandler {

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        val stackTrace = throwable.stackTraceToString()
        val tag = "CRASH"

        AppLogger.e(tag, "Uncaught exception on thread ${thread.name}: ${throwable.message}", throwable)

        saveCrashLog(thread, throwable, stackTrace)

        defaultHandler?.uncaughtException(thread, throwable)
    }

    private fun saveCrashLog(thread: Thread, throwable: Throwable, stackTrace: String) {
        try {
            val crashDir = File(context.filesDir, CRASH_LOG_DIR)
            crashDir.mkdirs()

            val dateFormat = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)
            val fileName = "crash_${dateFormat.format(Date())}.txt"
            val crashFile = File(crashDir, fileName)

            val report = buildString {
                appendLine("=== Crash Report ===")
                appendLine("Time: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())}")
                appendLine("Thread: ${thread.name}")
                appendLine("Exception: ${throwable.javaClass.name}")
                appendLine("Message: ${throwable.message}")
                appendLine()
                appendLine("=== Stack Trace ===")
                appendLine(stackTrace)
                appendLine()
                appendLine("=== App Logs (last 200 entries) ===")
                appendLine(AppLogger.getRecentLogs(200))
            }

            crashFile.writeText(report)
        } catch (_: Exception) {
            // Can't do much if crash logging itself fails
        }
    }

    companion object {
        /** Subdirectory name under `context.filesDir` where crash log files are stored. */
        const val CRASH_LOG_DIR = "crash_logs"
    }
}
