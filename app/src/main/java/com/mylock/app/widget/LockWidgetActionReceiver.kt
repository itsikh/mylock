package com.mylock.app.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.mylock.app.logging.AppLogger
import com.mylock.app.ttlock.TtlockRepository
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Handles the widget Unlock tap.
 *
 * If the geofence already marks the user as near home → unlock directly.
 * Otherwise → scan current GPS, compare to saved home coordinates; if within
 * [AppConfig.HOME_GEOFENCE_RADIUS_METERS] → mark near home and unlock; if still too far → skip.
 *
 * The actual unlock (proximity scan + network call) is delegated to [LockUnlockWorker] so it
 * runs inside a JobScheduler context, which is not subject to the background DNS restriction
 * that Samsung enforces on direct BroadcastReceiver background network calls (Android 16).
 */
@AndroidEntryPoint
class LockWidgetActionReceiver : BroadcastReceiver() {

    @Inject lateinit var ttlockRepository: TtlockRepository

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

        WorkManager.getInstance(context).enqueue(
            OneTimeWorkRequestBuilder<LockUnlockWorker>()
                .setInputData(workDataOf(
                    LockUnlockWorker.KEY_LOCK_ID to lockId,
                    LockUnlockWorker.KEY_LOCK_NAME to lockName
                ))
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
        )
    }
}
