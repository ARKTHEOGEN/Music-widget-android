package com.arktheogen.musicwidget

import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaControllerCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.annotation.MainThread
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Single source of truth for "what is playing and who controls it".
 *
 * How it works: Android lists every app's media session to apps holding
 * notification access. We keep one tracker per supported session, cache the
 * latest metadata/state the app pushes to us (so reads cost no IPC), pick the
 * session to show, and tell listeners (widget, panel) when something changed.
 */
@MainThread
object SessionHub {
    const val AUTO = "auto"
    private const val PREFS = "session_hub"
    private const val KEY_MODE = "mode"
    private const val KEY_LAST = "last_pkg"
    private const val DISPATCH_DELAY_MS = 60L

    fun interface Listener {
        fun onNowPlayingChanged()
    }

    private lateinit var appCtx: Context
    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<Listener>()
    private var sessionManager: MediaSessionManager? = null
    private var trackers: List<Tracker> = emptyList()
    private var current: Tracker? = null

    private val prefs get() = appCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun listenerComponent(ctx: Context) = ComponentName(ctx, MediaListenerService::class.java)

    fun hasAccess(ctx: Context): Boolean =
        ctx.getSystemService(NotificationManager::class.java)
            .isNotificationListenerAccessGranted(listenerComponent(ctx))

    /** Safe to call many times; does real work only the first time access is available. */
    fun ensureStarted(ctx: Context) {
        if (!::appCtx.isInitialized) {
            appCtx = ctx.applicationContext
            listeners += Listener { WidgetRenderer.renderAll(appCtx) }
        }
        if (sessionManager != null || !hasAccess(appCtx)) return
        val msm = appCtx.getSystemService(MediaSessionManager::class.java)
        val component = listenerComponent(appCtx)
        try {
            msm.addOnActiveSessionsChangedListener(sessionsListener, component, main)
            sessionManager = msm
            onSessionsChanged(msm.getActiveSessions(component))
        } catch (e: SecurityException) {
            // Access was revoked between the check and the call; stay idle until granted.
        }
    }

    fun stop() {
        sessionManager?.removeOnActiveSessionsChangedListener(sessionsListener)
        sessionManager = null
        onSessionsChanged(emptyList())
    }

    fun addListener(l: Listener) {
        listeners += l
    }

    fun removeListener(l: Listener) {
        listeners -= l
    }

    // ---------------------------------------------------------------- sessions

    private val sessionsListener =
        MediaSessionManager.OnActiveSessionsChangedListener { onSessionsChanged(it) }

    private fun onSessionsChanged(list: List<MediaController>?) {
        val supported = list.orEmpty().filter { MusicApps.isSupported(it.packageName) }
        val kept = trackers.associateBy { it.controller.sessionToken }
        val next = supported.map { fw ->
            val token = MediaSessionCompat.Token.fromToken(fw.sessionToken)
            kept[token] ?: Tracker(MediaControllerCompat(appCtx, token)).also { it.start() }
        }
        trackers.filterNot { it in next }.forEach { it.stop() }
        trackers = next
        reselect()
    }

    /** Chooses which session the widget shows, based on the user's mode. */
    private fun reselect() {
        val mode = mode()
        val chosen = if (mode == AUTO) {
            trackers.firstOrNull { it.isPlaying }
                ?: current?.takeIf { it in trackers } // keep the paused one, don't jump around
                ?: trackers.firstOrNull()
        } else {
            trackers.firstOrNull { it.pkg == mode }
        }
        if (chosen != null && chosen.pkg != prefs.getString(KEY_LAST, null)) {
            prefs.edit().putString(KEY_LAST, chosen.pkg).apply()
        }
        current = chosen
        // Apps often send metadata + state back to back; batch them into one redraw.
        main.removeCallbacks(dispatch)
        main.postDelayed(dispatch, DISPATCH_DELAY_MS)
    }

    private val dispatch = Runnable { listeners.forEach { it.onNowPlayingChanged() } }

    // ---------------------------------------------------------------- mode / switch

    fun mode(): String = prefs.getString(KEY_MODE, AUTO) ?: AUTO

    fun setMode(mode: String) {
        prefs.edit().putString(KEY_MODE, mode).apply()
        reselect()
    }

    private fun hasSessionFor(pkg: String) = trackers.any { it.pkg == pkg }

    /** Follow [pkg] (or AUTO). Opens the app if it isn't running yet. */
    fun select(ctx: Context, pkg: String) {
        setMode(pkg)
        if (pkg != AUTO && !hasSessionFor(pkg)) openApp(ctx, pkg)
    }

    /** Auto → each installed app → Auto. Never launches apps (that would be noisy). */
    fun cycleMode(ctx: Context): String {
        val order = listOf(AUTO) + MusicApps.installed(ctx).map { it.pkg }
        val next = order[(order.indexOf(mode()) + 1) % order.size]
        setMode(next)
        return next
    }

    /** The app a "play" tap should open when nothing is running. */
    fun targetPackage(): String? =
        current?.pkg ?: mode().takeIf { it != AUTO } ?: prefs.getString(KEY_LAST, null)

    fun openApp(ctx: Context, pkg: String? = targetPackage()): Boolean {
        val target = pkg ?: MusicApps.installed(ctx).firstOrNull()?.pkg ?: return false
        val intent = ctx.packageManager.getLaunchIntentForPackage(target)
            ?: Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$target"))
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            ctx.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            false
        }
    }

    // ---------------------------------------------------------------- commands

    fun playPause() {
        val t = current ?: return
        if (t.isPlaying) t.controls.pause() else t.controls.play()
    }

    fun next() {
        current?.controls?.skipToNext()
    }

    fun previous() {
        current?.controls?.skipToPrevious()
    }

    fun seekTo(ms: Long) {
        current?.controls?.seekTo(ms)
    }

    /** Uses the standard shuffle command if the app supports it, else the app's own button. */
    fun toggleShuffle() {
        val t = current ?: return
        if (t.supports(PlaybackStateCompat.ACTION_SET_SHUFFLE_MODE)) {
            val on = t.shuffleMode == PlaybackStateCompat.SHUFFLE_MODE_ALL ||
                t.shuffleMode == PlaybackStateCompat.SHUFFLE_MODE_GROUP
            t.controls.setShuffleMode(
                if (on) PlaybackStateCompat.SHUFFLE_MODE_NONE else PlaybackStateCompat.SHUFFLE_MODE_ALL
            )
        } else {
            t.customAction("shuffle")?.let { t.controls.sendCustomAction(it, null) }
        }
    }

    /** Off → All → One → Off, with the same fallback as shuffle. */
    fun cycleRepeat() {
        val t = current ?: return
        if (t.supports(PlaybackStateCompat.ACTION_SET_REPEAT_MODE)) {
            val next = when (t.repeatMode) {
                PlaybackStateCompat.REPEAT_MODE_NONE -> PlaybackStateCompat.REPEAT_MODE_ALL
                PlaybackStateCompat.REPEAT_MODE_ALL,
                PlaybackStateCompat.REPEAT_MODE_GROUP -> PlaybackStateCompat.REPEAT_MODE_ONE
                else -> PlaybackStateCompat.REPEAT_MODE_NONE
            }
            t.controls.setRepeatMode(next)
        } else {
            t.customAction("repeat")?.let { t.controls.sendCustomAction(it, null) }
        }
    }

    // ---------------------------------------------------------------- snapshot

    fun nowPlaying(): NowPlaying {
        val t = current
        val base = NowPlaying(
            app = MusicApps.byPkg(t?.pkg ?: targetPackage()),
            mode = mode(),
            hasAccess = hasAccess(appCtx),
        )
        if (t == null) return base
        val m = t.metadata
        val s = t.state
        val duration = m?.getLong(MediaMetadataCompat.METADATA_KEY_DURATION)?.coerceAtLeast(0) ?: 0
        return base.copy(
            hasSession = true,
            title = m.text(MediaMetadataCompat.METADATA_KEY_TITLE)
                ?: m.text(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE)
                ?: m?.description?.title?.toString().orEmpty(),
            artist = m.text(MediaMetadataCompat.METADATA_KEY_ARTIST)
                ?: m.text(MediaMetadataCompat.METADATA_KEY_ALBUM_ARTIST)
                ?: m.text(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE)
                ?: m?.description?.subtitle?.toString().orEmpty(),
            album = m.text(MediaMetadataCompat.METADATA_KEY_ALBUM).orEmpty(),
            art = m?.getBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART)
                ?: m?.getBitmap(MediaMetadataCompat.METADATA_KEY_ART)
                ?: m?.getBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON),
            durationMs = duration,
            positionMs = s?.position ?: 0,
            positionAt = s?.lastPositionUpdateTime ?: 0,
            speed = s?.playbackSpeed?.takeIf { it > 0f } ?: 1f,
            isPlaying = t.isPlaying,
            canSeek = duration > 0 && t.supports(PlaybackStateCompat.ACTION_SEEK_TO),
            shuffle = when {
                t.supports(PlaybackStateCompat.ACTION_SET_SHUFFLE_MODE) -> when (t.shuffleMode) {
                    PlaybackStateCompat.SHUFFLE_MODE_NONE -> Toggle.OFF
                    PlaybackStateCompat.SHUFFLE_MODE_ALL,
                    PlaybackStateCompat.SHUFFLE_MODE_GROUP -> Toggle.ON
                    else -> Toggle.UNKNOWN
                }
                t.customAction("shuffle") != null -> Toggle.UNKNOWN
                else -> Toggle.UNAVAILABLE
            },
            repeat = when {
                t.supports(PlaybackStateCompat.ACTION_SET_REPEAT_MODE) -> when (t.repeatMode) {
                    PlaybackStateCompat.REPEAT_MODE_NONE -> Repeat.OFF
                    PlaybackStateCompat.REPEAT_MODE_ALL,
                    PlaybackStateCompat.REPEAT_MODE_GROUP -> Repeat.ALL
                    PlaybackStateCompat.REPEAT_MODE_ONE -> Repeat.ONE
                    else -> Repeat.UNKNOWN
                }
                t.customAction("repeat") != null -> Repeat.UNKNOWN
                else -> Repeat.UNAVAILABLE
            },
        )
    }

    private fun MediaMetadataCompat?.text(key: String): String? =
        this?.getString(key)?.takeIf { it.isNotBlank() }

    /** Holds one app's session plus the last values it pushed to us. */
    private class Tracker(val controller: MediaControllerCompat) {
        val pkg: String = controller.packageName
        val controls: MediaControllerCompat.TransportControls get() = controller.transportControls
        var metadata: MediaMetadataCompat? = controller.metadata
        var state: PlaybackStateCompat? = controller.playbackState
        var shuffleMode: Int = controller.shuffleMode
        var repeatMode: Int = controller.repeatMode

        val isPlaying: Boolean
            get() = when (state?.state) {
                PlaybackStateCompat.STATE_PLAYING,
                PlaybackStateCompat.STATE_BUFFERING,
                PlaybackStateCompat.STATE_CONNECTING -> true
                else -> false
            }

        fun supports(action: Long): Boolean = ((state?.actions ?: 0L) and action) != 0L

        fun customAction(word: String): PlaybackStateCompat.CustomAction? =
            state?.customActions?.firstOrNull {
                it.action.contains(word, ignoreCase = true) ||
                    it.name?.toString()?.contains(word, ignoreCase = true) == true
            }

        private val callback = object : MediaControllerCompat.Callback() {
            override fun onMetadataChanged(m: MediaMetadataCompat?) {
                metadata = m
                reselect()
            }

            override fun onPlaybackStateChanged(s: PlaybackStateCompat?) {
                state = s
                reselect()
            }

            override fun onShuffleModeChanged(mode: Int) {
                shuffleMode = mode
                reselect()
            }

            override fun onRepeatModeChanged(mode: Int) {
                repeatMode = mode
                reselect()
            }

            override fun onSessionReady() {
                shuffleMode = controller.shuffleMode
                repeatMode = controller.repeatMode
                reselect()
            }
        }

        fun start() = controller.registerCallback(callback, main)

        fun stop() = controller.unregisterCallback(callback)
    }
}
