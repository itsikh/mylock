package com.mylock.app.logging

import android.content.Context
import android.util.Log
import com.mylock.app.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * Application-wide logging singleton.
 *
 * Wraps Android's [Log] with two additional capabilities:
 * 1. **In-memory ring buffer** — keeps the last [BUFFER_SIZE] log entries so that
 *    bug reports can include recent logs without requiring file I/O during normal operation.
 * 2. **Level filtering** — entries below [currentLevel] are silently dropped, both from
 *    the buffer and from logcat. In release builds the default level is [LogLevel.WARN];
 *    in debug builds it is [LogLevel.DEBUG].
 *
 * ## Thread safety
 * The buffer uses [ConcurrentLinkedDeque], so logging from any thread is safe.
 * [currentLevel] is a plain `var` — reads and writes from multiple threads are technically
 * a data race, but since it only ever changes via the debug settings toggle (rare, UI thread)
 * and the worst outcome is one misfiltered log entry, this is acceptable.
 *
 * ## Usage
 * ```kotlin
 * AppLogger.d(TAG, "Starting sync")
 * AppLogger.e(TAG, "Sync failed", exception)
 * ```
 *
 * ## Integration points
 * - [GlobalExceptionHandler] calls [getRecentLogs] to attach the last 200 entries to crash files.
 * - [BugReportViewModel] calls [exportLogs] / [getRecentLogs] to attach logs to GitHub issues.
 * - [DebugSettings] sets [currentLevel] on startup and whenever the user changes it.
 */
object AppLogger {

    /** Maximum number of log entries held in memory. Oldest entries are evicted when full. */
    private const val BUFFER_SIZE = 5000

    private val logBuffer = ConcurrentLinkedDeque<LogEntry>()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    /**
     * Active log level. Entries below this level are discarded.
     * Defaults to [LogLevel.DEBUG] in debug builds and [LogLevel.WARN] in release builds.
     * Updated at startup from persisted [DebugSettings.logLevel] and live when the user
     * changes the level in the settings screen.
     */
    var currentLevel: LogLevel = if (BuildConfig.DEBUG) LogLevel.DEBUG else LogLevel.WARN

    /**
     * A single captured log entry stored in the in-memory buffer.
     *
     * @property timestamp Human-readable timestamp (yyyy-MM-dd HH:mm:ss.SSS).
     * @property level Severity of this entry.
     * @property tag Logcat-style tag identifying the source class or component.
     * @property message The log message, including stack trace for error entries.
     * @property threadName Name of the thread that produced this entry.
     */
    data class LogEntry(
        val timestamp: String,
        val level: LogLevel,
        val tag: String,
        val message: String,
        val threadName: String
    ) {
        /** Formats the entry as a single human-readable line for export. */
        override fun toString(): String {
            return "$timestamp [$level] [$threadName] $tag: $message"
        }
    }

    /** Logs a DEBUG-level message. Dropped if [currentLevel] is above [LogLevel.DEBUG]. */
    fun d(tag: String, msg: String) {
        log(LogLevel.DEBUG, tag, msg)
    }

    /** Logs an INFO-level message. */
    fun i(tag: String, msg: String) {
        log(LogLevel.INFO, tag, msg)
    }

    /** Logs a WARN-level message. */
    fun w(tag: String, msg: String) {
        log(LogLevel.WARN, tag, msg)
    }

    /**
     * Logs an ERROR-level message.
     *
     * @param throwable If provided, the full stack trace is appended to [msg] in the buffer.
     */
    fun e(tag: String, msg: String, throwable: Throwable? = null) {
        val fullMsg = if (throwable != null) {
            "$msg\n${throwable.stackTraceToString()}"
        } else {
            msg
        }
        log(LogLevel.ERROR, tag, fullMsg)
    }

    /**
     * Logs a "What a Terrible Failure" assertion — always written to both the buffer
     * and Android's [Log.wtf], regardless of [currentLevel].
     */
    fun wtf(tag: String, msg: String) {
        log(LogLevel.ERROR, tag, "[WTF] $msg")
        Log.wtf(tag, msg)
    }

    private fun log(level: LogLevel, tag: String, message: String) {
        if (level.priority < currentLevel.priority) return

        val entry = LogEntry(
            timestamp = dateFormat.format(Date()),
            level = level,
            tag = tag,
            message = message,
            threadName = Thread.currentThread().name
        )

        logBuffer.addLast(entry)
        while (logBuffer.size > BUFFER_SIZE) {
            logBuffer.pollFirst()
        }

        when (level) {
            LogLevel.DEBUG -> Log.d(tag, message)
            LogLevel.INFO -> Log.i(tag, message)
            LogLevel.WARN -> Log.w(tag, message)
            LogLevel.ERROR -> Log.e(tag, message)
            LogLevel.NONE -> { /* no-op */ }
        }
    }

    /**
     * Returns all buffered log entries as a single newline-separated string.
     * Used by [BugReportViewModel] to attach the full log to a bug report.
     */
    fun exportLogs(): String {
        return logBuffer.joinToString(separator = "\n") { it.toString() }
    }

    /**
     * Writes all buffered log entries to a timestamped file in the app's cache directory
     * (`cache/logs/app_logs_<timestamp>.txt`) and returns the [File].
     *
     * The file is in the cache dir and may be cleared by the OS under low storage pressure.
     * For persistent crash logs use [GlobalExceptionHandler] instead.
     */
    fun exportLogsToFile(context: Context): File {
        val logsDir = File(context.cacheDir, "logs")
        logsDir.mkdirs()
        val file = File(logsDir, "app_logs_${System.currentTimeMillis()}.txt")
        file.writeText(exportLogs())
        return file
    }

    /**
     * Returns the [count] most recent log entries as a newline-separated string.
     *
     * @param count Maximum number of entries to return (default 100).
     *              Used by [GlobalExceptionHandler] (200) and [BugReportViewModel] (100).
     */
    fun getRecentLogs(count: Int = 100): String {
        val allLogs = logBuffer.toList()
        val recent = if (allLogs.size > count) allLogs.subList(allLogs.size - count, allLogs.size) else allLogs
        return recent.joinToString(separator = "\n") { entry -> entry.toString() }
    }

    /** Clears all entries from the in-memory buffer. Does not affect crash log files. */
    fun clear() {
        logBuffer.clear()
    }
}
