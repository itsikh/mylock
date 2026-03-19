package com.mylock.app.ui.components

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mylock.app.logging.DebugSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * Activity-scoped ViewModel that exposes the debug overlay state needed by [AppNavHost].
 *
 * Kept intentionally minimal — its only job is to convert [DebugSettings] flows into
 * [StateFlow]s that [AppNavHost] can observe without being inside a specific NavBackStackEntry.
 *
 * Because it is instantiated via `hiltViewModel()` at the [AppNavHost] level (outside any
 * `composable {}` block), it lives in the Activity's ViewModelStore and survives screen
 * navigation for as long as the Activity is alive.
 */
@HiltViewModel
class DebugOverlayViewModel @Inject constructor(
    debugSettings: DebugSettings
) : ViewModel() {

    /**
     * Whether the floating bug-report button should be shown on top of all screens.
     * Requires both admin mode AND the "show bug button" setting to be enabled.
     */
    val showBugButton: StateFlow<Boolean> = combine(
        debugSettings.adminMode,
        debugSettings.showBugButton
    ) { isAdmin, showButton -> isAdmin && showButton }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = false
    )
}
