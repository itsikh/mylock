package com.mylock.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.widget.RemoteViews
import com.mylock.app.R

/**
 * 1×1 home-screen widget — tap the entire cell to unlock.
 * Click is set on the root FrameLayout for maximum launcher compatibility.
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

        // Tint icon white via code (android:tint in XML is not supported in RemoteViews < API 31)
        views.setInt(R.id.widget_mini_button, "setColorFilter", Color.WHITE)

        val intent = Intent(context, LockWidgetActionReceiver::class.java).apply {
            action = LockWidgetProvider.ACTION_WIDGET_UNLOCK
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context, 1, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        // Click on ROOT for reliable tap on all launchers
        views.setOnClickPendingIntent(R.id.widget_mini_root, pendingIntent)

        manager.updateAppWidget(widgetId, views)
    }
}
