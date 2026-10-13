package com.ramiro.iosdroid

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Bundle

class GlassClockWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val state = WidgetRenderer.loadState(context)
        ids.forEach { WidgetRenderer.update(context, manager, it, state) }
        WeatherWorker.schedule(context)
        WeatherWorker.refreshIfStale(context)
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) {
        // Se redimensionó: otro layout y un vidrio a la nueva medida.
        WidgetRenderer.update(context, manager, id)
    }

    override fun onEnabled(context: Context) {
        WeatherWorker.schedule(context)
        WeatherWorker.refreshIfStale(context)
    }

    override fun onDisabled(context: Context) {
        WeatherWorker.cancel(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == Intent.ACTION_LOCALE_CHANGED) WidgetRenderer.updateAll(context)
    }
}
