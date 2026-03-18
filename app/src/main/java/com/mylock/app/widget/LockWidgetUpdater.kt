package com.mylock.app.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent

/** Helper that broadcasts an update request to all instances of [LockWidgetProvider]. */
object LockWidgetUpdater {
    fun update(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        val widgetIds = manager.getAppWidgetIds(
            ComponentName(context, LockWidgetProvider::class.java)
        )
        if (widgetIds.isNotEmpty()) {
            val intent = Intent(context, LockWidgetProvider::class.java).apply {
                action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, widgetIds)
            }
            context.sendBroadcast(intent)
        }
    }
}
