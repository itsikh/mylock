package com.mylock.app.widget

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.location.Location
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
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import javax.inject.Inject

/**
 * Handles the widget Unlock tap.
 *
 * If the geofence already marks the user as near home → unlock directly.
 * Otherwise → scan current GPS, compare to saved home coordinates; if within
 * [AppConfig.HOME_GEOFENCE_RADIUS_METERS] → mark near home and unlock; if still too far → skip.
 */
@AndroidEntryPoint
class LockWidgetActionReceiver : BroadcastReceiver() {

    @Inject lateinit var ttlockRepository: TtlockRepository
    @Inject lateinit var lockEventDao: LockEventDao

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        private const val TAG = "LockWidgetActionReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != LockWidgetProvider.ACTION_WIDGET_UNLOCK) return

        val lockId = ttlockRepository.getSelectedLockId()
        val lockName = ttlockRepository.getSelectedLockName() ?: "Door"

        if (lockId == null) {
            AppLogger.w(TAG, "No lock configured — ignoring widget tap")
            return
        }

        val pendingResult = goAsync()
        scope.launch {
            try {
                val isNearHome = context
                    .getSharedPreferences("geofence_state", Context.MODE_PRIVATE)
                    .getBoolean(GeofenceBroadcastReceiver.PREF_IS_NEAR_HOME, false)

                val confirmed = if (isNearHome) {
                    AppLogger.i(TAG, "Already near home — skipping scan")
                    true
                } else {
                    scanProximity(context)
                }

                if (!confirmed) {
                    AppLogger.i(TAG, "Not near home after scan — unlock blocked")
                    LockWidgetUpdater.update(context)
                    return@launch
                }

                val result = ttlockRepository.unlock(lockId)
                val eventType = when (result) {
                    is TtlockResult.Success -> {
                        AppLogger.i(TAG, "Widget unlock success for lock $lockId")
                        LockEventType.UNLOCK
                    }
                    is TtlockResult.Error -> {
                        AppLogger.e(TAG, "Widget unlock failed: ${result.message}")
                        LockEventType.UNLOCK_FAILED
                    }
                }
                lockEventDao.insert(
                    LockEvent(
                        eventType = eventType,
                        lockId = lockId,
                        lockName = lockName,
                        errorMessage = (result as? TtlockResult.Error)?.message
                    )
                )
                LockWidgetUpdater.update(context)
            } finally {
                pendingResult.finish()
            }
        }
    }

    /**
     * Checks distance to saved home using the device's cached location first (instant),
     * falling back to a fresh fix only if no cache is available.
     * If within radius, updates [GeofenceBroadcastReceiver.PREF_IS_NEAR_HOME] and returns true.
     */
    @SuppressLint("MissingPermission")
    private suspend fun scanProximity(context: Context): Boolean {
        return try {
            val homePrefs = context.getSharedPreferences("home_location", Context.MODE_PRIVATE)
            val homeLat = homePrefs.getFloat("lat", Float.MIN_VALUE)
            val homeLng = homePrefs.getFloat("lng", Float.MIN_VALUE)
            if (homeLat == Float.MIN_VALUE || homeLng == Float.MIN_VALUE) {
                AppLogger.w(TAG, "scanProximity: no home location saved")
                return false
            }

            val fusedClient = LocationServices.getFusedLocationProviderClient(context)

            // Try cached lastLocation first — instant, no GPS spin-up needed
            AppLogger.i(TAG, "scanProximity: trying lastLocation…")
            val lastLocation = suspendCancellableCoroutine<Location?> { cont ->
                fusedClient.lastLocation
                    .addOnSuccessListener { cont.resume(it) }
                    .addOnFailureListener { cont.resume(null) }
            }

            // Fall back to a fresh fix only if cache is empty
            val location = lastLocation ?: run {
                AppLogger.i(TAG, "scanProximity: no cached location, requesting fresh fix…")
                val cts = CancellationTokenSource()
                suspendCancellableCoroutine<Location?> { cont ->
                    cont.invokeOnCancellation { cts.cancel() }
                    fusedClient.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, cts.token)
                        .addOnSuccessListener { cont.resume(it) }
                        .addOnFailureListener { cont.resume(null) }
                }
            }

            if (location == null) {
                AppLogger.w(TAG, "scanProximity: could not get location")
                return false
            }

            val results = FloatArray(1)
            Location.distanceBetween(
                location.latitude, location.longitude,
                homeLat.toDouble(), homeLng.toDouble(),
                results
            )
            val distanceM = results[0]
            AppLogger.i(TAG, "scanProximity: distance = ${distanceM.toInt()} m (radius ${AppConfig.HOME_GEOFENCE_RADIUS_METERS.toInt()} m)")

            if (distanceM <= AppConfig.HOME_GEOFENCE_RADIUS_METERS) {
                context.getSharedPreferences("geofence_state", Context.MODE_PRIVATE)
                    .edit().putBoolean(GeofenceBroadcastReceiver.PREF_IS_NEAR_HOME, true).apply()
                true
            } else {
                false
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "scanProximity: exception: ${e.message}", e)
            false
        }
    }
}
