package com.template.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/**
 * Helper that detects a secret "developer tap" gesture and toggles admin mode.
 *
 * Admin mode is activated by tapping a designated UI element (typically the version string
 * in the About section) [REQUIRED_CLICKS] times in succession. This is the same pattern
 * used by Android's "Build number" easter egg in Developer Options.
 *
 * ## Usage
 * 1. Create an instance via [rememberAdminModeHelper] (Compose) or construct directly.
 * 2. Call [onVersionClick] from the `onClick` handler of the tappable UI element, passing
 *    the current admin mode state.
 * 3. The [onToggle] callback fires when the tap count reaches [REQUIRED_CLICKS], with the
 *    new desired state (`true` to enable, `false` to disable).
 *
 * The counter resets to zero after each toggle so tapping 7 more times will toggle again.
 * Call [reset] explicitly if you want to cancel an in-progress tap sequence.
 *
 * ## Integration
 * [SettingsScaffold] wires this up to the version string text in the About card,
 * and shows a Toast message when admin mode is toggled.
 *
 * @param onToggle Called with the desired new admin mode state when the tap threshold is reached.
 */
class AdminModeHelper(
    private val onToggle: (Boolean) -> Unit
) {
    private var clickCount = 0

    /**
     * Records one tap. Calls [onToggle] and resets the counter when [REQUIRED_CLICKS] is reached.
     *
     * @param currentAdminMode The current admin mode state, used to compute the next state.
     */
    fun onVersionClick(currentAdminMode: Boolean) {
        clickCount++
        if (clickCount >= REQUIRED_CLICKS) {
            onToggle(!currentAdminMode)
            clickCount = 0
        }
    }

    /** Resets the tap counter without toggling admin mode. */
    fun reset() {
        clickCount = 0
    }

    companion object {
        /** Number of consecutive taps required to toggle admin mode. */
        const val REQUIRED_CLICKS = 7
    }
}

/**
 * Composable factory for [AdminModeHelper] that survives recomposition.
 *
 * The returned helper is remembered by [onToggle] identity, so it is only recreated
 * if the callback changes. Use this instead of constructing [AdminModeHelper] directly
 * inside a composable.
 *
 * @param onToggle Passed through to [AdminModeHelper]. Should be stable (e.g. a lambda
 *                 that calls a ViewModel function).
 */
@Composable
fun rememberAdminModeHelper(onToggle: (Boolean) -> Unit): AdminModeHelper {
    return remember(onToggle) { AdminModeHelper(onToggle) }
}
