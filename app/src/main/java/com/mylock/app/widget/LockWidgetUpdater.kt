package com.mylock.app.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent

/** Broadcasts an update request to all instances of both widget providers. */
object LockWidgetUpdater {
    fun update(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        listOf(LockWidgetProvider::class.java, LockWidgetMiniProvider::class.java).forEach { cls ->
            val ids = manager.getAppWidgetIds(ComponentName(context, cls))
            if (ids.isNotEmpty()) {
                context.sendBroadcast(Intent(context, cls).apply {
                    action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
                })
            }
        }
    }
}
