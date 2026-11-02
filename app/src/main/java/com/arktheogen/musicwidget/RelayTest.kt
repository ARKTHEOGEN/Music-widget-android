package com.arktheogen.musicwidget

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import org.json.JSONException
import java.io.IOException
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Relay test (Feature 2, option A): YouTube Music picks, Apple Music plays.
 *  - YouTube Music only supplies picks: we read its up-next list and never move or load it,
 *    so it never plays and never takes the speaker (runs 2-3: moving it paused Apple Music).
 *  - Apple Music plays exactly what we ask: we find the song's Apple ID ourselves
 *    ([AppleCatalog]) and try three ways to hand it over, logging which one Apple Music accepts.
 * Runs [SONGS] songs, each jumped to [TAIL_MS] before its end to keep the test short.
 * Test code: it polls. The real feature would react to session callbacks instead.
 */
object RelayTest {
    private const val YT = "com.google.android.apps.youtube.music"
    private const val AM = "com.apple.android.music"
    private const val SONGS = 12
    private const val TAIL_MS = 15_000L
    /** Send the next pick this early: 0.7 s let Apple Music's own autoplay win (run 1). */
    private const val HANDOFF_EARLY_MS = 2_000L
    private const val ATTEMPT_MS = 6_000L

    private enum class Way { MEDIA_ID, URI, SEARCH }

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var ctx: Context
    private var log: (String) -> Unit = {}
    private var running = false
    private var step = 0
    private var lastQueueId = -1L
    private var track: AppleCatalog.Track? = null
    private var expected = Diagnostics.Song("", "")
    private var playingTitle = ""
    private var interim = ""
    private var way = 0
    private var requestedAt = 0L
    private var pickedAt = 0L

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
        lastQueueId = yt.playbackState?.activeQueueItemId ?: -1L
        log("")
        log("> RELAY test v3: YouTube Music picks (read only), Apple Music plays exact IDs " +
            "($SONGS songs, each jumped to ${TAIL_MS / 1000} s before its end; country ${AppleCatalog.country(ctx)})")
        log("  YouTube Music list: ${queue.size} songs, current id $lastQueueId")
        yt.transportControls.pause() // it was playing the song we started from; we never touch it again
        next(activity)
    }

    fun stop(reason: String) {
        if (!running) return
        running = false
        main.removeCallbacksAndMessages(null)
        log("  relay stopped: $reason")
        log("  done")
    }

    /** [activity] is null when called from the background, where Android won't let us open an app. */
    private fun next(activity: Activity?) {
        if (!running) return
        if (step >= SONGS) return stop("finished $SONGS songs")
        val yt = session(YT) ?: return stop("YouTube Music session disappeared")
        val queue = yt.queue.orEmpty()
        val item = pickAfter(queue, lastQueueId)
            ?: return stop("used up YouTube Music's list (${queue.size} songs); it adds no picks while we don't move it")
        lastQueueId = item.queueId
        step++
        val d = item.description
        val ytTitle = d.title?.toString().orEmpty()
        val ytArtist = d.subtitle?.toString().orEmpty()
        val left = queue.size - 1 - queue.indexOf(item)
        log("  [$step] YouTube Music pick: \"$ytTitle\" / $ytArtist (list ${queue.size}, $left after this)")
        pickedAt = SystemClock.elapsedRealtime()
        worker.execute {
            var error: String? = null
            val found = try {
                AppleCatalog.find(ctx, ytTitle, ytArtist)
            } catch (e: IOException) {
                error = e.message
                null
            } catch (e: JSONException) {
                error = "bad reply"
                null
            }
            main.post {
                if (!running) return@post
                if (found == null) {
                    log("      not found in Apple Music${error?.let { " (lookup failed: $it)" } ?: ""}: skipping")
                    return@post next(activity)
                }
                track = found
                expected = Diagnostics.Song(found.title, found.artist, durationMs = found.durationMs)
                log("      Apple match: \"${found.title}\" / ${found.artist} (${formatTime(found.durationMs)}), id ${found.id}" +
                    " [${SystemClock.elapsedRealtime() - pickedAt} ms]")
                play(activity)
            }
        }
    }

    /** The YouTube Music item after [afterId] (the first item if [afterId] isn't in the list). */
    private fun pickAfter(queue: List<MediaSession.QueueItem>, afterId: Long): MediaSession.QueueItem? =
        queue.getOrNull(queue.indexOfFirst { it.queueId == afterId } + 1)

    /** Apple Music idle (no player loaded) ignores session commands, so open it with the intent first. */
    private fun play(activity: Activity?) {
        val am = session(AM)
        val loaded = am != null && am.playbackState?.state.let { it != null && it != PlaybackState.STATE_NONE }
        if (!loaded && activity != null) {
            try {
                activity.startActivity(Diagnostics.searchIntent(AM, expected))
            } catch (e: ActivityNotFoundException) {
                return stop("Apple Music doesn't accept the intent")
            }
            log("      sent: intent play-from-search (Apple Music not loaded)")
            way = Way.SEARCH.ordinal
            requestedAt = SystemClock.elapsedRealtime()
            main.postDelayed(::waitForStart, 500)
            return
        }
        way = 0
        send()
    }

    private fun send() {
        val am = session(AM) ?: return stop("Apple Music session disappeared")
        val t = track ?: return
        val c = am.transportControls
        val w = Way.entries[way]
        when (w) {
            Way.MEDIA_ID -> c.playFromMediaId(t.id.toString(), null)
            Way.URI -> c.playFromUri(Uri.parse("https://music.apple.com/${AppleCatalog.country(ctx)}/song/${t.id}"), null)
            Way.SEARCH -> c.playFromSearch("${t.title} ${t.artist}", Diagnostics.searchExtras(expected))
        }
        log("      sent: ${wayName()}")
        requestedAt = SystemClock.elapsedRealtime()
        main.postDelayed(::waitForStart, 500)
    }

    /** Started = playing AND the title matches the Apple song we chose. */
    private fun waitForStart() {
        if (!running) return
        val am = session(AM)
        val got = am?.let { Diagnostics.Song.from(it) }
        val matches = got != null && !Diagnostics.verdict(expected, got).startsWith("MISMATCH")
        val state = am?.playbackState?.state
        if (matches && state == PlaybackState.STATE_PAUSED) {
            am.transportControls.play()
            log("      our song is loaded but paused: resumed it")
        }
        if (matches && state == PlaybackState.STATE_PLAYING) {
            val secs = (SystemClock.elapsedRealtime() - requestedAt) / 1000.0
            interim = ""
            main.postDelayed({ confirm(secs) }, 1000) // let the details settle
            return
        }
        if (got != null && state == PlaybackState.STATE_PLAYING && got.title != interim && got.title != playingTitle) {
            interim = got.title
            log("      meanwhile Apple Music plays \"${got.title}\" (not ours)")
        }
        if (SystemClock.elapsedRealtime() - requestedAt < ATTEMPT_MS) {
            main.postDelayed(::waitForStart, 500)
            return
        }
        log("      ${wayName()}: no start within ${ATTEMPT_MS / 1000} s")
        if (way < Way.entries.lastIndex) {
            way++
            send()
        } else {
            log("      Apple Music didn't play it any way: skipping")
            next(null)
        }
    }

    private fun confirm(secs: Double) {
        if (!running) return
        val am = session(AM) ?: return stop("Apple Music session disappeared")
        val got = Diagnostics.Song.from(am)
        playingTitle = got.title
        log("      PLAYING via ${wayName()} after ${String.format(Locale.ROOT, "%.1f", secs)} s " +
            "(${(SystemClock.elapsedRealtime() - pickedAt) / 1000} s since pick): \"${got.title}\" — ${Diagnostics.verdict(expected, got)}")
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
                log("      song ending ($remaining ms left): next pick")
                next(null)
            }
            state == PlaybackState.STATE_STOPPED || state == PlaybackState.STATE_NONE -> {
                log("      Apple Music stopped (${Diagnostics.stateName(state)}): next pick")
                next(null)
            }
            state == PlaybackState.STATE_PAUSED && remaining > HANDOFF_EARLY_MS + 1000 -> {
                log("      Apple Music paused mid-song: resumed it")
                am.transportControls.play()
                main.postDelayed(::watchEnd, 1000)
            }
            else -> main.postDelayed(::watchEnd, 250)
        }
    }

    private fun wayName() = Way.entries[way].name.lowercase(Locale.ROOT)

    private fun session(pkg: String) = Diagnostics.sessions(ctx)?.firstOrNull { it.packageName == pkg }
}
