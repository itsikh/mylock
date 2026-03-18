package com.mylock.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.mylock.app.R

/**
 * Home-screen widget that shows a single Unlock button.
 *
 * The button is enabled only when the user is near home (geofence entered).
 * Tapping it fires [LockWidgetService] which authenticates and calls the TTLock API.
 */
class LockWidgetProvider : AppWidgetProvider() {

    companion object {
        const val ACTION_WIDGET_UNLOCK = "com.mylock.app.ACTION_WIDGET_UNLOCK"
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        appWidgetIds.forEach { widgetId ->
            updateWidget(context, appWidgetManager, widgetId)
        }
    }

    private fun updateWidget(
        context: Context,
        manager: AppWidgetManager,
        widgetId: Int
    ) {
        val isNearHome = context
            .getSharedPreferences("geofence_state", Context.MODE_PRIVATE)
            .getBoolean("is_near_home", false)

        val views = RemoteViews(context.packageName, R.layout.lock_widget)

        // Status text
        views.setTextViewText(
            R.id.widget_status_text,
            if (isNearHome) context.getString(R.string.widget_tap_to_unlock)
            else context.getString(R.string.widget_too_far)
        )

        // Unlock button — enabled only when near home
        views.setBoolean(R.id.widget_unlock_button, "setEnabled", isNearHome)

        val unlockIntent = Intent(context, LockWidgetActionReceiver::class.java).apply {
            action = ACTION_WIDGET_UNLOCK
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context, 0, unlockIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.widget_unlock_button, pendingIntent)

        manager.updateAppWidget(widgetId, views)
    }
}
