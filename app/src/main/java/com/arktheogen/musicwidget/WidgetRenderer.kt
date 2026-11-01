package com.arktheogen.musicwidget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.SizeF
import android.view.View
import android.widget.RemoteViews
import androidx.core.graphics.drawable.toBitmap
import kotlin.math.abs

/**
 * Draws every placed widget.
 *
 * Efficiency rules:
 *  - Redraw only when something visible changed (signature check).
 *  - The elapsed-time clock is a Chronometer: the launcher ticks it by itself,
 *    so we never wake up every second.
 *  - One RemoteViews per size bucket; Android picks the right one on resize
 *    without calling us.
 */
object WidgetRenderer {
    private val LAYOUTS = linkedMapOf(
        SizeF(40f, 40f) to R.layout.widget_tiny,
        SizeF(220f, 40f) to R.layout.widget_bar,
        SizeF(110f, 110f) to R.layout.widget_square,
        SizeF(220f, 110f) to R.layout.widget_medium,
        SizeF(200f, 220f) to R.layout.widget_large,
    )
    private val CHIP_IDS = intArrayOf(R.id.chip_0, R.id.chip_1, R.id.chip_2)
    private const val SEEK_TOLERANCE_MS = 1500L

    private var lastSignature: String? = null
    private var lastBase = 0L
    private val iconCache = HashMap<String, Bitmap>()

    fun renderAll(ctx: Context, force: Boolean = false) {
        val mgr = AppWidgetManager.getInstance(ctx)
        val ids = mgr.getAppWidgetIds(ComponentName(ctx, PlayerWidgetProvider::class.java))
        if (ids.isEmpty()) return
        SessionHub.ensureStarted(ctx)
        val np = SessionHub.nowPlaying()
        val now = SystemClock.elapsedRealtime()
        val base = now - np.livePositionMs(now)
        val signature = np.widgetSignature()
        // Skip if nothing visible changed and the clock hasn't jumped (i.e. no seek).
        if (!force && signature == lastSignature && abs(base - lastBase) < SEEK_TOLERANCE_MS) return
        lastSignature = signature
        lastBase = base

        val look = Artwork.lookFor(ctx, np.art)
        val views = RemoteViews(LAYOUTS.mapValues { (_, layout) -> fill(ctx, layout, np, look, base) })
        mgr.updateAppWidget(ids, views)
    }

    private fun fill(ctx: Context, layout: Int, np: NowPlaying, look: Artwork.Look, base: Long): RemoteViews {
        val v = RemoteViews(ctx.packageName, layout)
        v.setInt(R.id.bg_tint, "setColorFilter", look.background)

        if (look.widgetBitmap != null) v.setImageViewBitmap(R.id.art, look.widgetBitmap)
        else v.setImageViewResource(R.id.art, R.drawable.art_placeholder)

        val (title, subtitle) = when {
            !np.hasAccess -> ctx.getString(R.string.widget_setup_title) to ctx.getString(R.string.widget_setup_subtitle)
            !np.hasSession -> ctx.getString(R.string.nothing_playing) to
                (np.app?.let { ctx.getString(R.string.tap_play_to_open, it.label) } ?: ctx.getString(R.string.open_a_music_app))
            else -> np.title to np.artist
        }
        v.setTextViewText(R.id.title, title)
        v.setTextViewText(R.id.artist, subtitle)

        // Play / pause
        v.setImageViewResource(R.id.play_icon, if (np.isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
        v.setContentDescription(R.id.btn_play, ctx.getString(if (np.isPlaying) R.string.cd_pause else R.string.cd_play))
        v.setInt(R.id.play_bg, "setColorFilter", look.accent)
        v.setInt(R.id.play_icon, "setColorFilter", look.onAccent)

        // Time (Chronometer ticks on its own; we only set where it starts)
        v.setViewVisibility(R.id.time_row, if (np.hasSession && np.durationMs > 0) View.VISIBLE else View.GONE)
        v.setChronometer(R.id.elapsed, base, null, np.isPlaying)
        v.setTextViewText(R.id.duration, " / " + formatTime(np.durationMs))

        // Shuffle / repeat
        v.setInt(R.id.btn_shuffle, "setImageAlpha", alphaFor(np.shuffle))
        v.setImageViewResource(R.id.btn_repeat, if (np.repeat == Repeat.ONE) R.drawable.ic_repeat_one else R.drawable.ic_repeat)
        v.setInt(R.id.btn_repeat, "setImageAlpha", alphaFor(np.repeat))

        // Source app badge + switcher chips
        val badge = np.app?.let { appIcon(ctx, it.pkg) }
        if (badge != null) v.setImageViewBitmap(R.id.app_badge, badge)
        else v.setImageViewResource(R.id.app_badge, R.drawable.ic_music_note)
        v.setInt(R.id.chip_auto, "setBackgroundResource", if (np.mode == SessionHub.AUTO) R.drawable.chip_selected else 0)
        MusicApps.ALL.forEachIndexed { i, app ->
            val id = CHIP_IDS[i]
            val icon = appIcon(ctx, app.pkg)
            if (icon != null) v.setImageViewBitmap(id, icon) else v.setImageViewResource(id, R.drawable.ic_music_note)
            v.setInt(id, "setImageAlpha", if (icon != null) 255 else 90)
            v.setInt(id, "setBackgroundResource", if (np.mode == app.pkg) R.drawable.chip_selected else 0)
        }

        setClicks(ctx, v, np)
        return v
    }

    private fun setClicks(ctx: Context, v: RemoteViews, np: NowPlaying) {
        if (!np.hasAccess) {
            val setup = activity(ctx, 1, Intent(ctx, MainActivity::class.java))
            listOf(android.R.id.background, R.id.art, R.id.btn_play, R.id.btn_next, R.id.btn_prev,
                R.id.btn_shuffle, R.id.btn_repeat, R.id.btn_lyrics, R.id.app_badge, R.id.text_area,
                R.id.chip_auto, *CHIP_IDS.toTypedArray()).forEach { v.setOnClickPendingIntent(it, setup) }
            return
        }
        val panel = activity(ctx, 2, panelIntent(ctx, lyrics = false))
        v.setOnClickPendingIntent(R.id.art, panel)
        v.setOnClickPendingIntent(R.id.text_area, panel)
        v.setOnClickPendingIntent(R.id.btn_lyrics, activity(ctx, 3, panelIntent(ctx, lyrics = true)))

        v.setOnClickPendingIntent(
            R.id.btn_play,
            if (np.hasSession) broadcast(ctx, WidgetActionReceiver.PLAY_PAUSE)
            else activity(ctx, 4, SwitchActivity.openIntent(ctx, np.app?.pkg)),
        )
        v.setOnClickPendingIntent(R.id.btn_next, broadcast(ctx, WidgetActionReceiver.NEXT))
        v.setOnClickPendingIntent(R.id.btn_prev, broadcast(ctx, WidgetActionReceiver.PREVIOUS))
        v.setOnClickPendingIntent(R.id.btn_shuffle, broadcast(ctx, WidgetActionReceiver.SHUFFLE))
        v.setOnClickPendingIntent(R.id.btn_repeat, broadcast(ctx, WidgetActionReceiver.REPEAT))

        v.setOnClickPendingIntent(R.id.app_badge, activity(ctx, 5, SwitchActivity.cycleIntent(ctx)))
        v.setOnClickPendingIntent(R.id.chip_auto, activity(ctx, 10, SwitchActivity.selectIntent(ctx, SessionHub.AUTO)))
        MusicApps.ALL.forEachIndexed { i, app ->
            v.setOnClickPendingIntent(CHIP_IDS[i], activity(ctx, 11 + i, SwitchActivity.selectIntent(ctx, app.pkg)))
        }
    }

    private fun alphaFor(t: Toggle) = when (t) {
        Toggle.ON -> 255
        Toggle.UNKNOWN -> 200
        Toggle.OFF -> 110
        Toggle.UNAVAILABLE -> 50
    }

    private fun alphaFor(r: Repeat) = when (r) {
        Repeat.ALL, Repeat.ONE -> 255
        Repeat.UNKNOWN -> 200
        Repeat.OFF -> 110
        Repeat.UNAVAILABLE -> 50
    }

    private fun appIcon(ctx: Context, pkg: String): Bitmap? = iconCache[pkg]
        ?: MusicApps.icon(ctx, pkg)?.toBitmap(96, 96)?.also { iconCache[pkg] = it }

    fun panelIntent(ctx: Context, lyrics: Boolean) =
        Intent(ctx, PlayerPanelActivity::class.java)
            .putExtra(PlayerPanelActivity.EXTRA_SHOW_LYRICS, lyrics)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun activity(ctx: Context, code: Int, intent: Intent): PendingIntent =
        PendingIntent.getActivity(ctx, code, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun broadcast(ctx: Context, action: String): PendingIntent =
        PendingIntent.getBroadcast(
            ctx, action.hashCode(),
            Intent(ctx, WidgetActionReceiver::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
}
