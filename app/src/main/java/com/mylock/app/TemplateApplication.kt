package com.mylock.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.mylock.app.logging.GlobalExceptionHandler
import dagger.hilt.android.HiltAndroidApp

/**
 * Application entry point for the template app.
 *
 * Responsibilities:
 * - Annotated with [@HiltAndroidApp] to trigger Hilt's code generation and make DI
 *   available across the whole application.
 * - Installs [GlobalExceptionHandler] as the default uncaught exception handler so that
 *   any crash on any thread is captured to a file before the system default handler runs.
 *   This allows crash logs to be included in the next bug report the user submits.
 * - Creates the backup notification channel ([AppConfig.NOTIFICATION_CHANNEL_BACKUP]) used
 *   by [backup.BaseBackupManager] to post "Backup ready — tap to save" notifications.
 *
 * ## Adding initialization
 * Place one-time startup work (SDK inits, analytics, etc.) in [onCreate] after `super.onCreate()`.
 * Keep it lightweight — heavy work should be deferred to a background coroutine.
 *
 * ## Hilt
 * Because this class is annotated with [@HiltAndroidApp], it is the root of the Hilt
 * component hierarchy. All [@Singleton] scoped objects live as long as this Application.
 */
@HiltAndroidApp
class TemplateApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        Thread.setDefaultUncaughtExceptionHandler(
            GlobalExceptionHandler(this, Thread.getDefaultUncaughtExceptionHandler())
        )
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                AppConfig.NOTIFICATION_CHANNEL_BACKUP,
                "Backup",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notifications for completed backups that are ready to save"
            }
        )
    }
}
