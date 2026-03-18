package com.mylock.app.geofence

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import com.mylock.app.logging.AppLogger
import com.mylock.app.widget.LockWidgetUpdater

/**
 * Receives geofence transition events from the OS.
 * Updates the widget enabled/disabled state based on proximity to home.
 */
class GeofenceBroadcastReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "GeofenceReceiver"
        const val PREF_IS_NEAR_HOME = "is_near_home"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val event = GeofencingEvent.fromIntent(intent) ?: return
        if (event.hasError()) {
            AppLogger.e(TAG, "Geofencing error: ${event.errorCode}")
            return
        }

        val isNearHome = when (event.geofenceTransition) {
            Geofence.GEOFENCE_TRANSITION_ENTER, Geofence.GEOFENCE_TRANSITION_DWELL -> true
            Geofence.GEOFENCE_TRANSITION_EXIT -> false
            else -> return
        }

        AppLogger.i(TAG, "Geofence transition: isNearHome=$isNearHome")

        // Persist state so widget can read it without a coroutine
        context.getSharedPreferences("geofence_state", Context.MODE_PRIVATE)
            .edit()
            .putBoolean(PREF_IS_NEAR_HOME, isNearHome)
            .apply()

        // Redraw all active widgets
        LockWidgetUpdater.update(context)
    }
}
