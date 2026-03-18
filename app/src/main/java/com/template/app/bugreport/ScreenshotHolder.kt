package com.template.app.bugreport

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream

/**
 * In-process singleton that temporarily holds a compressed screenshot captured by
 * [ui.components.FloatingBugButton] until [ui.screens.bugreport.BugReportViewModel] consumes it.
 *
 * The holder is cleared on [take] so each screenshot is consumed exactly once.
 * Thread-safe via [@Volatile].
 */
object ScreenshotHolder {

    @Volatile private var pendingBytes: ByteArray? = null

    /** Compresses [bitmap] as JPEG (80% quality) and stores it for the next [take] call. */
    fun store(bitmap: Bitmap) {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out)
        pendingBytes = out.toByteArray()
    }

    /**
     * Returns and clears the stored screenshot bytes, or `null` if nothing is pending.
     * Consume-once — subsequent calls return `null` until [store] is called again.
     */
    fun take(): ByteArray? {
        val bytes = pendingBytes
        pendingBytes = null
        return bytes
    }
}
