package com.arktheogen.musicwidget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

/**
 * The right-edge strip: lock, auto brightness, QR scanner, the pop-up.
 * Colours come from the system theme (Material You), so it changes with wallpaper and dark mode.
 * Redraws only when something it shows changed (lock service, auto brightness, its permission).
 */
class SidePanelWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) = renderAll(ctx, force = true)

    companion object {
        private var lastSignature: String? = null

        fun renderAll(ctx: Context, force: Boolean = false) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, SidePanelWidget::class.java))
            if (ids.isEmpty()) return
            val lockOn = LockService.isEnabled(ctx)
            val canChange = Brightness.canChange(ctx)
            val auto = AutoBrightness.isOn(ctx)
            val signature = "$lockOn/$canChange/$auto"
            if (!force && signature == lastSignature) return // volume and other setting changes don't affect the strip
            lastSignature = signature

            val v = RemoteViews(ctx.packageName, R.layout.widget_panel)
            v.setOnClickPendingIntent(
                R.id.btn_lock,
                if (lockOn) broadcast(ctx, SidePanelReceiver.LOCK)
                else activity(ctx, 0, Intent(ctx, SidePanelSetupActivity::class.java)),
            )
            v.setImageViewResource(R.id.btn_brightness, if (auto) R.drawable.ic_brightness_auto else R.drawable.ic_brightness)
            v.setInt(R.id.btn_brightness, "setBackgroundResource", if (auto) R.drawable.tile_bg_on else R.drawable.tile_bg)
            v.setContentDescription(R.id.btn_brightness, ctx.getString(if (auto) R.string.cd_auto_on else R.string.cd_auto_off))
            v.setOnClickPendingIntent(
                R.id.btn_brightness,
                if (canChange) broadcast(ctx, SidePanelReceiver.BRIGHTNESS)
                else activity(ctx, 3, Intent(ctx, SidePanelSetupActivity::class.java)), // permission first
            )
            v.setOnClickPendingIntent(R.id.btn_qr, activity(ctx, 1, QuickPanelActivity.intent(ctx, QuickPanelActivity.DO_QR)))
            v.setOnClickPendingIntent(R.id.btn_menu, activity(ctx, 2, QuickPanelActivity.intent(ctx, null)))
            mgr.updateAppWidget(ids, v)
        }

        private fun activity(ctx: Context, code: Int, intent: Intent): PendingIntent =
            PendingIntent.getActivity(ctx, code, intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        private fun broadcast(ctx: Context, what: String): PendingIntent =
            PendingIntent.getBroadcast(ctx, what.hashCode(),
                Intent(ctx, SidePanelReceiver::class.java).setAction(what),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }
}
