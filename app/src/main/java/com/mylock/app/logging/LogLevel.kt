package com.mylock.app.logging

/**
 * Log severity levels used by [AppLogger].
 *
 * Levels are ordered by [priority]: DEBUG (0) → INFO (1) → WARN (2) → ERROR (3) → NONE (4).
 * [AppLogger] filters out any entry whose [priority] is below [AppLogger.currentLevel].
 *
 * | Level  | Use for |
 * |--------|---------|
 * | DEBUG  | Verbose development traces; stripped in release builds by default |
 * | INFO   | Normal operational events worth recording |
 * | WARN   | Unexpected but recoverable situations |
 * | ERROR  | Failures that affect functionality |
 * | NONE   | Disables all logging when set as [AppLogger.currentLevel] |
 *
 * The active level is persisted across sessions via [DebugSettings.logLevel] and
 * toggled from the settings screen's admin-only debug section.
 */
enum class LogLevel(val priority: Int) {
    DEBUG(0),
    INFO(1),
    WARN(2),
    ERROR(3),
    NONE(4)
}
