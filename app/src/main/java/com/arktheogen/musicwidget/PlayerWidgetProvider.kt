package com.arktheogen.musicwidget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context

/** Android calls this when a widget is added or needs a full redraw. */
class PlayerWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        SessionHub.ensureStarted(ctx)
        WidgetRenderer.renderAll(ctx, force = true)
    }
}
