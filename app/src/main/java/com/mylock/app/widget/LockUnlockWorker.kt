package com.mylock.app.widget

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
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
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

@EntryPoint
@InstallIn(SingletonComponent::class)
interface LockUnlockWorkerEntryPoint {
    fun ttlockRepository(): TtlockRepository
    fun lockEventDao(): LockEventDao
}

/**
 * Performs the widget unlock flow inside a WorkManager job so that it runs in a
 * JobScheduler context, which is exempt from Samsung's background DNS restriction
 * that causes instant UnknownHostException when the same call is made directly
 * from a BroadcastReceiver on Android 16.
 */
class LockUnlockWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "LockUnlockWorker"
        const val KEY_LOCK_ID = "lock_id"
        const val KEY_LOCK_NAME = "lock_name"
    }

    override suspend fun doWork(): Result {
        val lockId = inputData.getLong(KEY_LOCK_ID, -1L)
        val lockName = inputData.getString(KEY_LOCK_NAME) ?: "Door"
        if (lockId == -1L) return Result.failure()

        val entryPoint = EntryPointAccessors.fromApplication(
            applicationContext,
            LockUnlockWorkerEntryPoint::class.java
        )
        val ttlockRepository = entryPoint.ttlockRepository()
        val lockEventDao = entryPoint.lockEventDao()

        val isNearHome = applicationContext
            .getSharedPreferences("geofence_state", Context.MODE_PRIVATE)
            .getBoolean(GeofenceBroadcastReceiver.PREF_IS_NEAR_HOME, false)

        val confirmed = if (isNearHome) {
            AppLogger.i(TAG, "Already near home — skipping scan")
            true
        } else {
            scanProximity(applicationContext)
        }

        if (!confirmed) {
            AppLogger.i(TAG, "Not near home after scan — unlock blocked")
            LockWidgetUpdater.update(applicationContext)
            return Result.success()
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
        LockWidgetUpdater.update(applicationContext)
        return Result.success()
    }

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

            AppLogger.i(TAG, "scanProximity: trying lastLocation…")
            val lastLocation = suspendCancellableCoroutine<Location?> { cont ->
                fusedClient.lastLocation
                    .addOnSuccessListener { cont.resume(it) }
                    .addOnFailureListener { cont.resume(null) }
            }

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
