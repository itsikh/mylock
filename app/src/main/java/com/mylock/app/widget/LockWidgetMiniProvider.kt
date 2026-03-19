package com.mylock.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.mylock.app.R

/**
 * 1×1 home-screen widget — a single full-bleed button that fires the unlock action.
 * No text, no state display — just tap and the door opens (with the same scan-first
 * proximity check as [LockWidgetActionReceiver]).
 */
class LockWidgetMiniProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        appWidgetIds.forEach { updateWidget(context, appWidgetManager, it) }
    }

    private fun updateWidget(context: Context, manager: AppWidgetManager, widgetId: Int) {
        val views = RemoteViews(context.packageName, R.layout.lock_widget_mini)

        val intent = Intent(context, LockWidgetActionReceiver::class.java).apply {
            action = LockWidgetProvider.ACTION_WIDGET_UNLOCK
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context, 1, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.widget_mini_button, pendingIntent)

        manager.updateAppWidget(widgetId, views)
    }
}
