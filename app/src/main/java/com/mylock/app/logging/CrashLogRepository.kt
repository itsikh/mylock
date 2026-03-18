package com.mylock.app.logging

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for reading and managing crash log files written by [GlobalExceptionHandler].
 *
 * Crash logs are plain-text files stored in `filesDir/crash_logs/`. Each file represents
 * one crash event and contains the exception details plus the last 200 app log entries
 * captured at the moment of the crash.
 *
 * ## Typical usage
 * - [BugReportViewModel] calls [hasCrashLogs] at construction time to determine whether
 *   to show the "crash log attached" indicator, and [getLatestCrashLog] to include it
 *   in the GitHub issue body.
 * - A settings screen can call [clearCrashLogs] to let the user clean up old reports.
 *
 * Injected as a [@Singleton] so it can be shared across ViewModels without re-scanning
 * the filesystem on every access.
 */
@Singleton
class CrashLogRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val crashDir: File
        get() = File(context.filesDir, GlobalExceptionHandler.CRASH_LOG_DIR)

    /**
     * Returns `true` if the crash log directory exists and contains at least one file.
     * Cheap to call — only performs a directory existence and listing check.
     */
    fun hasCrashLogs(): Boolean {
        return crashDir.exists() && crashDir.listFiles()?.isNotEmpty() == true
    }

    /**
     * Returns the content of the most recently modified crash log file, or `null` if
     * no crash logs exist. The returned string may be several KB.
     */
    fun getLatestCrashLog(): String? {
        val files = crashDir.listFiles() ?: return null
        val latestFile = files.maxByOrNull { it.lastModified() } ?: return null
        return latestFile.readText()
    }

    /**
     * Returns the content of all crash log files, sorted newest-first.
     * Each element in the list is the full text of one crash report.
     * Returns an empty list if no crash logs exist.
     */
    fun getAllCrashLogs(): List<String> {
        val files = crashDir.listFiles() ?: return emptyList()
        return files.sortedByDescending { it.lastModified() }.map { it.readText() }
    }

    /**
     * Deletes all crash log files. Does not delete the directory itself.
     * Call this after the user has submitted a bug report or explicitly cleared logs.
     */
    fun clearCrashLogs() {
        crashDir.listFiles()?.forEach { it.delete() }
    }
}
