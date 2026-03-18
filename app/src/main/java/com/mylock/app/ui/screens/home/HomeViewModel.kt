package com.mylock.app.ui.screens.home

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mylock.app.data.LockEvent
import com.mylock.app.data.LockEventDao
import com.mylock.app.data.LockEventType
import com.mylock.app.geofence.GeofenceBroadcastReceiver
import com.mylock.app.logging.AppLogger
import com.mylock.app.ttlock.TtlockRepository
import com.mylock.app.ttlock.TtlockResult
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HomeUiState(
    val lockName: String = "",
    val isConfigured: Boolean = false,
    val isNearHome: Boolean = false,
    val isLoading: Boolean = false,
    val lastActionSuccess: Boolean? = null,   // null = no action yet
    val errorMessage: String? = null
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val ttlockRepository: TtlockRepository,
    private val lockEventDao: LockEventDao
) : ViewModel() {

    companion object {
        private const val TAG = "HomeViewModel"
    }

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState

    val recentEvents: StateFlow<List<LockEvent>> = lockEventDao
        .getRecentEvents()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        refresh()
    }

    fun refresh() {
        val lockId = ttlockRepository.getSelectedLockId()
        val lockName = ttlockRepository.getSelectedLockName() ?: ""
        val isNearHome = context
            .getSharedPreferences("geofence_state", Context.MODE_PRIVATE)
            .getBoolean(GeofenceBroadcastReceiver.PREF_IS_NEAR_HOME, false)

        _uiState.value = _uiState.value.copy(
            isConfigured = lockId != null && ttlockRepository.hasCredentials(),
            lockName = lockName,
            isNearHome = isNearHome
        )
    }

    fun unlock() {
        val lockId = ttlockRepository.getSelectedLockId() ?: return
        val lockName = ttlockRepository.getSelectedLockName() ?: "Door"

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
            when (val result = ttlockRepository.unlock(lockId)) {
                is TtlockResult.Success -> {
                    AppLogger.i(TAG, "Unlock success")
                    lockEventDao.insert(LockEvent(eventType = LockEventType.UNLOCK, lockId = lockId, lockName = lockName))
                    _uiState.value = _uiState.value.copy(isLoading = false, lastActionSuccess = true)
                }
                is TtlockResult.Error -> {
                    AppLogger.e(TAG, "Unlock failed: ${result.message}")
                    lockEventDao.insert(LockEvent(eventType = LockEventType.UNLOCK_FAILED, lockId = lockId, lockName = lockName, errorMessage = result.message))
                    _uiState.value = _uiState.value.copy(isLoading = false, lastActionSuccess = false, errorMessage = result.message)
                }
            }
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(errorMessage = null, lastActionSuccess = null)
    }
}
