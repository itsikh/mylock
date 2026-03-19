package com.mylock.app.ui.screens.home

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.mylock.app.AppConfig
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
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
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

    /**
     * Called when the unlock button is tapped.
     * If already near home → unlock immediately.
     * If not → scan current GPS, compare to saved home; if within radius → mark near + unlock.
     */
    fun handleUnlockTap() {
        if (_uiState.value.isNearHome) {
            unlock()
        } else {
            scanAndUnlock()
        }
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

    /**
     * Gets current GPS, checks distance to saved home. If within [AppConfig.HOME_GEOFENCE_RADIUS_METERS],
     * marks isNearHome=true in SharedPreferences and UI state, then fires the unlock.
     */
    @SuppressLint("MissingPermission")
    private fun scanAndUnlock() {
        val lockId = ttlockRepository.getSelectedLockId() ?: return
        val lockName = ttlockRepository.getSelectedLockName() ?: "Door"

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
            try {
                val homePrefs = context.getSharedPreferences("home_location", Context.MODE_PRIVATE)
                val homeLat = homePrefs.getFloat("lat", Float.MIN_VALUE)
                val homeLng = homePrefs.getFloat("lng", Float.MIN_VALUE)
                if (homeLat == Float.MIN_VALUE || homeLng == Float.MIN_VALUE) {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        errorMessage = "No home location set. Go to Settings → Home Location first."
                    )
                    return@launch
                }

                AppLogger.i(TAG, "scanAndUnlock: fetching current location…")
                val cts = CancellationTokenSource()
                val location = suspendCancellableCoroutine<Location?> { cont ->
                    cont.invokeOnCancellation { cts.cancel() }
                    LocationServices.getFusedLocationProviderClient(context)
                        .getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cts.token)
                        .addOnSuccessListener { cont.resume(it) }
                        .addOnFailureListener { cont.resume(null) }
                }

                if (location == null) {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        errorMessage = "Could not get GPS fix. Enable location and try again."
                    )
                    return@launch
                }

                val results = FloatArray(1)
                Location.distanceBetween(
                    location.latitude, location.longitude,
                    homeLat.toDouble(), homeLng.toDouble(),
                    results
                )
                val distanceM = results[0]
                AppLogger.i(TAG, "scanAndUnlock: distance to home = ${distanceM.toInt()} m (radius ${AppConfig.HOME_GEOFENCE_RADIUS_METERS.toInt()} m)")

                if (distanceM <= AppConfig.HOME_GEOFENCE_RADIUS_METERS) {
                    // Near home — update state and proceed with unlock
                    context.getSharedPreferences("geofence_state", Context.MODE_PRIVATE)
                        .edit().putBoolean(GeofenceBroadcastReceiver.PREF_IS_NEAR_HOME, true).apply()
                    _uiState.value = _uiState.value.copy(isNearHome = true)

                    when (val result = ttlockRepository.unlock(lockId)) {
                        is TtlockResult.Success -> {
                            AppLogger.i(TAG, "scanAndUnlock: unlock success")
                            lockEventDao.insert(LockEvent(eventType = LockEventType.UNLOCK, lockId = lockId, lockName = lockName))
                            _uiState.value = _uiState.value.copy(isLoading = false, lastActionSuccess = true)
                        }
                        is TtlockResult.Error -> {
                            AppLogger.e(TAG, "scanAndUnlock: unlock failed: ${result.message}")
                            lockEventDao.insert(LockEvent(eventType = LockEventType.UNLOCK_FAILED, lockId = lockId, lockName = lockName, errorMessage = result.message))
                            _uiState.value = _uiState.value.copy(isLoading = false, lastActionSuccess = false, errorMessage = result.message)
                        }
                    }
                } else {
                    AppLogger.i(TAG, "scanAndUnlock: too far (${distanceM.toInt()} m)")
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        errorMessage = "Still too far (${distanceM.toInt()} m away). Move closer and try again."
                    )
                }
            } catch (e: SecurityException) {
                _uiState.value = _uiState.value.copy(isLoading = false, errorMessage = "Location permission required")
            } catch (e: Exception) {
                AppLogger.e(TAG, "scanAndUnlock: exception: ${e.message}", e)
                _uiState.value = _uiState.value.copy(isLoading = false, errorMessage = e.message ?: "Unknown error")
            }
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(errorMessage = null, lastActionSuccess = null)
    }
}
