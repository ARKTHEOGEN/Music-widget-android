package com.arktheogen.musicwidget

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.Locale

/**
 * Relay test (Feature 2, option A): YouTube Music's up-next list picks, Apple Music plays.
 * Runs [SONGS] songs. To keep it short, each Apple Music song is jumped to [TAIL_MS] before its end.
 * Answers: does Apple Music start each pick? Does YouTube Music stay quiet when we move its list on,
 * and does the list keep growing? How long is the gap, and does Apple's own autoplay get in first?
 * Test code: it polls. The real feature would react to session callbacks instead.
 */
object RelayTest {
    private const val YT = "com.google.android.apps.youtube.music"
    private const val AM = "com.apple.android.music"
    private const val SONGS = 12
    private const val TAIL_MS = 15_000L
    private const val START_TIMEOUT_MS = 15_000L
    /** Send the next pick this early: 0.7 s let Apple Music's own autoplay win (run 1). */
    private const val HANDOFF_EARLY_MS = 2_000L
    private const val YT_WATCH_MS = 2_500L

    private val main = Handler(Looper.getMainLooper())
    private lateinit var ctx: Context
    private var log: (String) -> Unit = {}
    private var running = false
    private var step = 0
    private var expected = Diagnostics.Song("", "")
    private var playingTitle = ""
    private var requestedAt = 0L
    private var interim = ""
    private var seenIds = setOf<Long>()

    fun start(activity: Activity, log: (String) -> Unit) {
        if (running) {
            log("Relay test already running; tap Stop first.")
            return
        }
        this.log = log
        ctx = activity.applicationContext
        val yt = session(YT) ?: run { log("Relay: play a song in YouTube Music first."); return }
        val queue = yt.queue.orEmpty()
        if (queue.isEmpty()) {
            log("Relay: YouTube Music shared no up-next list.")
            return
        }
        running = true
        step = 0
        seenIds = queue.map { it.queueId }.toSet()
        log("")
        log("> RELAY test: YouTube Music picks, Apple Music plays ($SONGS songs, each jumped to ${TAIL_MS / 1000} s before its end)")
        log("  YouTube Music list: ${queue.size} songs, current id ${yt.playbackState?.activeQueueItemId}")
        yt.transportControls.pause()
        next(activity)
    }

    fun stop(reason: String) {
        if (!running) return
        running = false
        main.removeCallbacksAndMessages(null)
        log("  relay stopped: $reason")
        log("  done")
    }

    /**
     * [activity] is null when called from the background, where Android won't let us open an app.
     * Order matters (run 2): moving YouTube Music's place makes it start playing and take the speaker
     * (audio focus), and Apple Music then pauses itself. So move YouTube Music first, pause it as soon
     * as it starts, and only then start Apple Music, which takes the speaker last.
     */
    private fun next(activity: Activity?) {
        if (step >= SONGS) return stop("finished $SONGS songs")
        val yt = session(YT) ?: return stop("YouTube Music session disappeared")
        val item = nextItem(yt) ?: return stop("YouTube Music list ran out")
        step++
        val d = item.description
        expected = Diagnostics.Song(d.title?.toString().orEmpty(), d.subtitle?.toString().orEmpty())
        log("  [$step] YouTube Music pick: \"${expected.title}\" / ${expected.artist}")
        yt.transportControls.skipToQueueItem(item.queueId)
        val sentAt = SystemClock.elapsedRealtime()
        val check = object : Runnable {
            override fun run() {
                if (!running) return
                val y = session(YT) ?: return stop("YouTube Music session disappeared")
                val state = y.playbackState?.state
                val elapsed = SystemClock.elapsedRealtime() - sentAt
                val started = state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_BUFFERING
                if (!started && elapsed < YT_WATCH_MS) {
                    main.postDelayed(this, 100)
                    return
                }
                if (started) y.transportControls.pause()
                val queue = y.queue.orEmpty()
                val fresh = queue.count { it.queueId !in seenIds }
                seenIds = seenIds + queue.map { it.queueId }
                log("      YouTube Music moved to id ${y.playbackState?.activeQueueItemId} (sent ${item.queueId}); " +
                    (if (started) "started by itself after $elapsed ms, paused it" else "stayed quiet") +
                    "; list ${queue.size} songs, $fresh new")
                requestedAt = SystemClock.elapsedRealtime()
                log("      method: ${playInApple(activity)}")
                main.postDelayed(::waitForStart, 500)
            }
        }
        main.post(check)
    }

    /** The item after YouTube Music's current one (the first item if it reports none). */
    private fun nextItem(yt: MediaController): MediaSession.QueueItem? {
        val queue = yt.queue.orEmpty()
        val active = yt.playbackState?.activeQueueItemId ?: MediaSession.QueueItem.UNKNOWN_ID.toLong()
        return queue.getOrNull(queue.indexOfFirst { it.queueId == active } + 1)
    }

    private fun playInApple(activity: Activity?): String {
        val am = session(AM)
        val query = "${expected.title} ${expected.artist}".trim()
        val loaded = am != null && am.playbackState?.state.let { it != null && it != PlaybackState.STATE_NONE }
        if (am != null && (loaded || activity == null)) {
            am.transportControls.playFromSearch(query, Diagnostics.searchExtras(expected))
            return "session play-from-search " + if (loaded) "(Apple Music loaded)" else "(Apple Music idle; can't open it from the background)"
        }
        if (activity == null) return "FAILED: Apple Music closed and we're in the background"
        return try {
            activity.startActivity(Diagnostics.searchIntent(AM, expected))
            "intent play-from-search (Apple Music ${if (am == null) "closed" else "idle"})"
        } catch (e: ActivityNotFoundException) {
            "FAILED: Apple Music doesn't accept the intent"
        }
    }

    /** Started = playing AND the title matches our pick (run 1 mistook Apple's autoplay song for ours). */
    private fun waitForStart() {
        if (!running) return
        val am = session(AM)
        val got = am?.let { Diagnostics.Song.from(it) }
        val matches = got != null && !Diagnostics.verdict(expected, got).startsWith("MISMATCH")
        if (matches && am?.playbackState?.state == PlaybackState.STATE_PAUSED) {
            am.transportControls.play()
            log("      our pick is loaded but paused (lost the speaker?): resumed it")
        }
        if (am?.playbackState?.state == PlaybackState.STATE_PLAYING && got != null) {
            if (!Diagnostics.verdict(expected, got).startsWith("MISMATCH")) {
                val secs = (SystemClock.elapsedRealtime() - requestedAt) / 1000.0
                interim = ""
                main.postDelayed({ confirm(secs) }, 1000) // let the details settle
                return
            }
            if (got.title != interim && got.title != playingTitle) {
                interim = got.title
                log("      meanwhile Apple Music plays \"${got.title}\" (not our pick)")
            }
        }
        if (SystemClock.elapsedRealtime() - requestedAt > START_TIMEOUT_MS) return stop("Apple Music did not start song $step within 15 s")
        main.postDelayed(::waitForStart, 500)
    }

    private fun confirm(secs: Double) {
        if (!running) return
        val am = session(AM) ?: return stop("Apple Music session disappeared")
        val got = Diagnostics.Song.from(am)
        playingTitle = got.title
        log("      Apple Music playing after ${String.format(Locale.ROOT, "%.1f", secs)} s: \"${got.title}\" / ${got.artist} (${formatTime(got.durationMs)})")
        log("      match: ${Diagnostics.verdict(expected, got)}")
        if (got.durationMs > TAIL_MS * 2) {
            am.transportControls.seekTo(got.durationMs - TAIL_MS)
            log("      jumped to ${formatTime(got.durationMs - TAIL_MS)} (test shortcut)")
        }
        main.postDelayed(::watchEnd, 1000)
    }

    private fun watchEnd() {
        if (!running) return
        val am = session(AM) ?: return stop("Apple Music session disappeared")
        val got = Diagnostics.Song.from(am)
        val state = am.playbackState?.state
        val remaining = got.durationMs - got.positionMs
        when {
            got.title.isNotBlank() && got.title != playingTitle -> {
                log("      Apple Music's own autoplay started \"${got.title}\" first")
                next(null)
            }
            state == PlaybackState.STATE_PLAYING && got.durationMs > 0 && remaining <= HANDOFF_EARLY_MS -> {
                log("      song ending ($remaining ms left): sending the next pick")
                next(null)
            }
            state == PlaybackState.STATE_STOPPED || state == PlaybackState.STATE_NONE -> {
                log("      Apple Music stopped (${Diagnostics.stateName(state)}): sending the next pick")
                next(null)
            }
            state == PlaybackState.STATE_PAUSED && remaining > HANDOFF_EARLY_MS + 1000 -> {
                log("      Apple Music paused mid-song (lost the speaker?): resumed it")
                am.transportControls.play()
                main.postDelayed(::watchEnd, 1000)
            }
            else -> main.postDelayed(::watchEnd, 250)
        }
    }

    private fun session(pkg: String) = Diagnostics.sessions(ctx)?.firstOrNull { it.packageName == pkg }
}
