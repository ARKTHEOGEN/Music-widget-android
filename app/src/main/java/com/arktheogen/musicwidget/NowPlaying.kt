package com.arktheogen.musicwidget

import android.graphics.Bitmap
import android.os.SystemClock
import java.util.Locale

enum class Toggle { UNAVAILABLE, UNKNOWN, OFF, ON }

enum class Repeat { UNAVAILABLE, UNKNOWN, OFF, ALL, ONE }

/** One read-only picture of the player state, shared by the widget and the panel. */
data class NowPlaying(
    val app: MusicApp?,
    /** YouTube Music picks, Apple Music plays ([Relay]). */
    val relay: Boolean,
    val hasAccess: Boolean,
    val hasSession: Boolean = false,
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val art: Bitmap? = null,
    val durationMs: Long = 0,
    val positionMs: Long = 0,
    /** SystemClock.elapsedRealtime() when [positionMs] was measured. */
    val positionAt: Long = 0,
    val speed: Float = 1f,
    val isPlaying: Boolean = false,
    val canSeek: Boolean = false,
    val shuffle: Toggle = Toggle.UNAVAILABLE,
    val repeat: Repeat = Repeat.UNAVAILABLE,
) {
    val trackKey: String get() = "${app?.pkg}|$title|$artist"

    /** Position right now, projected forward from the last report while playing. */
    fun livePositionMs(now: Long = SystemClock.elapsedRealtime()): Long {
        val pos = if (isPlaying && positionAt > 0) {
            positionMs + ((now - positionAt) * speed).toLong()
        } else positionMs
        return if (durationMs > 0) pos.coerceIn(0, durationMs) else pos.coerceAtLeast(0)
    }

    /** Fields that change what the widget shows (position is handled separately). */
    fun widgetSignature(): String = listOf(
        hasAccess, hasSession, relay, app?.pkg, title, artist, isPlaying,
        shuffle, repeat, durationMs, System.identityHashCode(art),
    ).joinToString("|")
}

fun formatTime(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s)
    else String.format(Locale.ROOT, "%d:%02d", m, s)
}
