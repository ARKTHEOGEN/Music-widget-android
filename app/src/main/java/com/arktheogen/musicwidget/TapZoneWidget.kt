package com.arktheogen.musicwidget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.widget.RemoteViews

/**
 * An invisible widget to stretch over empty home-screen space: double-tap it to lock.
 * The Pixel launcher has no double-tap-to-lock, and an app can't see taps on the home screen
 * itself, so this zone is how Side Panel receives them. One tap does nothing.
 */
class TapZoneWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        val v = RemoteViews(ctx.packageName, R.layout.widget_tapzone)
        v.setOnClickPendingIntent(
            R.id.zone,
            PendingIntent.getBroadcast(
                ctx, 0, Intent(ctx, SidePanelReceiver::class.java).setAction(SidePanelReceiver.TAP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )
        mgr.updateAppWidget(ids, v)
    }

    companion object {
        /** Two taps closer together than this lock the screen (Android's own double-tap time is 300 ms). */
        private const val DOUBLE_TAP_MS = 400L
        private var lastTap = 0L

        /** True on the second tap of a double tap. */
        fun isDoubleTap(): Boolean {
            val now = SystemClock.uptimeMillis()
            val double = now - lastTap < DOUBLE_TAP_MS
            lastTap = if (double) 0L else now
            return double
        }
    }
}
