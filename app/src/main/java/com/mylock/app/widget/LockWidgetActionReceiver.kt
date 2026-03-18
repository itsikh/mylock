package com.mylock.app.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.mylock.app.data.LockEvent
import com.mylock.app.data.LockEventDao
import com.mylock.app.data.LockEventType
import com.mylock.app.logging.AppLogger
import com.mylock.app.ttlock.TtlockRepository
import com.mylock.app.ttlock.TtlockResult
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Handles the widget Unlock tap.
 * Runs the TTLock API call on a background coroutine (no Activity context available).
 * Biometric auth is NOT enforced here since the widget is already on the secure lock screen
 * or requires device unlock to dismiss — that is sufficient for the home widget use-case.
 * For additional security, set requireBiometric = true in the future.
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
}
