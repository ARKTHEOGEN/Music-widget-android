package com.arktheogen.musicwidget

import android.app.Activity
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.annotation.MainThread
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Relay: YouTube Music picks, Apple Music plays.
 *  - Our own list ([items], saved to disk): YouTube Music's picks in order, each with its Apple
 *    song once found. [pos] is the song playing now; Next and Previous move along this list.
 *  - YouTube Music is only read (its up-next list), so it stays silent. One exception, [refill]:
 *    when the list runs out it is moved to its last song and paused at once, so it adds picks.
 *  - Apple Music is told to play exact song IDs found by [AppleCatalog].
 *  - Driven by Apple Music's session callbacks plus one timer per song (no polling).
 * Every step is logged (adb logcat -s MusicWidgetDiag) so tests can be read back.
 */
@MainThread
object Relay {
    private const val YT = "com.google.android.apps.youtube.music"
    private const val AM = "com.apple.android.music"
    private const val TAG = "MusicWidgetDiag"
    private const val FILE = "relay.json"
    private const val MAX_ITEMS = 300
    /** Ask for the next song this early; 0.7 s let Apple Music's own autoplay win (relay run 1). */
    private const val HANDOFF_EARLY_MS = 1_500L
    /** Ask Apple Music to load the next song this early, so it can start without a gap. */
    private const val PREPARE_AHEAD_MS = 12_000L
    private const val ATTEMPT_MS = 8_000L
    /** Previous within this much of a song's start goes back a song; later, it restarts the song. */
    private const val RESTART_MS = 5_000L
    /** Fast test: each song is jumped to this far before its end. */
    private const val FAST_TAIL_MS = 25_000L
    private const val REFILL_WAIT_MS = 4_000L

    private enum class Via { MEDIA_ID, SEARCH, PREPARE }

    private class Item(val title: String, val artist: String) {
        var apple: AppleCatalog.Track? = null
        var missing = false
        /** Waiting for the lookup; callers queue here so one lookup serves all. */
        var waiting: MutableList<(AppleCatalog.Track?) -> Unit>? = null
    }

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var ctx: Context
    private var ui: ((String) -> Unit)? = null

    // Saved
    private val items = ArrayList<Item>()
    private var pos = -1
    private var ytLastId = -1L
    private var prepareWorks = true

    // Running
    var isOn = false
        private set
    private var fast = false
    private var am: MediaController? = null
    /** The song we asked Apple Music for and haven't seen playing yet (-1: none). */
    private var pendingIdx = -1
    private var pendingVia = Via.MEDIA_ID
    private var requestedAt = 0L
    private var endingAt = 0L
    private var endsAt = 0L
    private var lastRefillId = -1L
    private var refilling = false

    /** Lines also go to [sink] (the diagnostics screen) while it's open. */
    fun setUi(sink: ((String) -> Unit)?) {
        ui = sink
    }

    // ------------------------------------------------------------------ start / stop

    /**
     * [fastTest] jumps each song near its end, to test many handoffs quickly.
     * Returns why it couldn't start, or null when it started.
     */
    fun start(activity: Activity, fastTest: Boolean): String? {
        ctx = activity.applicationContext
        if (isOn) return null
        if (!MusicApps.isInstalled(ctx, AM) || !MusicApps.isInstalled(ctx, YT)) {
            return ctx.getString(R.string.relay_needs_apps).also { log(it) }
        }
        load()
        fast = fastTest
        val yt = session(YT)
        if (yt != null) {
            val added = absorb(yt)
            if (yt.playbackState?.state == PlaybackState.STATE_PLAYING) yt.transportControls.pause()
            log("YouTube Music: +$added picks (our list ${items.size}, now at ${pos + 1})")
        }
        if (items.isEmpty()) return ctx.getString(R.string.relay_needs_pick).also { log(it) }
        isOn = true
        SessionHub.relayChanged()
        log("ON${if (fast) " (fast test: each song jumps to ${FAST_TAIL_MS / 1000} s before its end)" else ""}; " +
            "country ${AppleCatalog.country(ctx)}; prepare-ahead ${if (prepareWorks) "on" else "off (it cut songs short)"}")
        // The next unplayed pick (a new YouTube Music list starts with its current song); else replay the last.
        val firstIdx = (pos + 1).coerceAtMost(items.size - 1)
        if (!attach()) {
            // Apple Music idle ignores session commands; opening it loads its player.
            log("Apple Music isn't loaded: opening it")
            SessionHub.openApp(activity, AM)
            waitForApple(firstIdx, tries = 30)
            return null
        }
        playIdx(firstIdx, forward = true)
        return null
    }

    /** The badge tap: switches the relay and returns what to tell the owner. */
    fun toggle(activity: Activity): String {
        if (isOn) {
            stop("turned off by you")
            return activity.getString(R.string.relay_off)
        }
        return start(activity, fastTest = false) ?: activity.getString(R.string.relay_on)
    }

    fun stop(reason: String) {
        if (!isOn) return
        isOn = false
        main.removeCallbacksAndMessages(null)
        refilling = false
        am?.unregisterCallback(appleCallback)
        am = null
        pendingIdx = -1
        save()
        log("OFF: $reason")
        SessionHub.relayChanged()
    }

    private fun waitForApple(idx: Int, tries: Int) {
        if (!isOn) return
        // Run 1 of step 1: 5 s wasn't enough. After 3 s, try even a session with no player loaded.
        if (attach() || (tries <= 24 && am != null)) {
            log("Apple Music is up (${Diagnostics.stateName(am?.playbackState?.state)})")
            return playIdx(idx, forward = true)
        }
        if (tries == 0) return stop("Apple Music didn't start within 15 s")
        main.postDelayed({ waitForApple(idx, tries - 1) }, 500)
    }

    /** Hooks Apple Music's session. False when it has none, or no loaded player. */
    private fun attach(): Boolean {
        val s = session(AM) ?: return false
        if (am?.sessionToken != s.sessionToken) {
            am?.unregisterCallback(appleCallback)
            am = s
            s.registerCallback(appleCallback, main)
            logAutoplay(s)
        }
        return s.playbackState?.state.let { it != null && it != PlaybackState.STATE_NONE }
    }

    // ------------------------------------------------------------------ next / previous

    fun next() {
        if (!isOn) return
        log("you: next")
        advance()
    }

    fun previous() {
        if (!isOn) return
        val c = am ?: return
        val at = c.playbackState?.let { positionNow(it) } ?: 0L
        if (at > RESTART_MS || pos <= 0) {
            log("you: previous (restart this song)")
            c.transportControls.seekTo(0)
            return
        }
        log("you: previous")
        playIdx(pos - 1, forward = false)
    }

    /** To the next pick; refills from YouTube Music when our list has run out. */
    private fun advance() {
        if (pos + 1 < items.size) return playIdx(pos + 1, forward = true)
        val yt = session(YT) ?: return stop("YouTube Music isn't running (no more picks)")
        if (absorb(yt) > 0) return playIdx(pos + 1, forward = true)
        val at = pos
        refill(yt) {
            if (pos != at) return@refill // you moved on meanwhile
            if (pos + 1 < items.size) playIdx(pos + 1, forward = true)
            else stop("YouTube Music gave no more picks")
        }
    }

    // ------------------------------------------------------------------ YouTube Music picks

    /**
     * Copies YouTube Music's picks we don't have yet. A list we've seen continues after the last
     * pick we took; a new list (owner started another song there) starts at its current song.
     */
    private fun absorb(yt: MediaController): Int {
        val queue = yt.queue.orEmpty()
        val last = queue.indexOfFirst { it.queueId == ytLastId }
        val from = if (last >= 0) last + 1
        else queue.indexOfFirst { it.queueId == yt.playbackState?.activeQueueItemId }.coerceAtLeast(0)
        if (last < 0) {
            // A new list: drop picks of the old list we haven't played.
            while (items.size > pos + 1) items.removeAt(items.size - 1)
        }
        val fresh = queue.drop(from)
        fresh.forEach { items += Item(it.description.title?.toString().orEmpty(), it.description.subtitle?.toString().orEmpty()) }
        fresh.lastOrNull()?.let { ytLastId = it.queueId }
        trim()
        if (fresh.isNotEmpty()) save()
        return fresh.size
    }

    /**
     * Test: YouTube Music adds picks only when it moves. Move it to its last song and pause it
     * the moment it starts, between two Apple songs. Logs how long it was audible and how many
     * picks it added.
     */
    private fun refill(yt: MediaController, then: () -> Unit) {
        val queue = yt.queue.orEmpty()
        val target = queue.lastOrNull() ?: return then()
        if (target.queueId == lastRefillId) {
            log("refill: already tried from this song; YouTube Music added nothing")
            return then()
        }
        lastRefillId = target.queueId
        val before = queue.size
        val t0 = SystemClock.elapsedRealtime()
        var playingAt = 0L
        var pausedAt = 0L
        val cb = object : MediaController.Callback() {
            override fun onPlaybackStateChanged(s: PlaybackState?) {
                val st = s?.state ?: return
                val now = SystemClock.elapsedRealtime()
                if (playingAt == 0L && (st == PlaybackState.STATE_PLAYING || st == PlaybackState.STATE_BUFFERING)) {
                    playingAt = now
                    yt.transportControls.pause()
                    log("refill: YouTube Music ${Diagnostics.stateName(st)} after ${now - t0} ms: paused it")
                }
                if (playingAt > 0 && pausedAt == 0L && st == PlaybackState.STATE_PAUSED) pausedAt = now
            }

            override fun onQueueChanged(q: MutableList<MediaSession.QueueItem>?) {
                log("refill: YouTube Music list now ${q?.size ?: 0} (+${(q?.size ?: 0) - before}) at ${SystemClock.elapsedRealtime() - t0} ms")
            }
        }
        refilling = true
        yt.registerCallback(cb, main)
        log("refill: moving YouTube Music to its last song (list $before) to get more picks")
        yt.transportControls.skipToQueueItem(target.queueId)
        main.postDelayed({
            yt.unregisterCallback(cb)
            refilling = false
            if (yt.playbackState?.state == PlaybackState.STATE_PLAYING) yt.transportControls.pause()
            val audible = when {
                playingAt == 0L -> "never started"
                pausedAt == 0L -> "still not paused after ${REFILL_WAIT_MS / 1000} s"
                else -> "audible about ${pausedAt - playingAt} ms"
            }
            val added = absorb(yt)
            log("refill: done; YouTube Music $audible; +$added new picks")
            then()
        }, REFILL_WAIT_MS)
    }

    // ------------------------------------------------------------------ Apple Music

    /** Finds the Apple song for [idx] (saved or looked up), then asks Apple Music to play it. */
    private fun playIdx(idx: Int, forward: Boolean) {
        if (!isOn) return
        val item = items.getOrNull(idx) ?: return advance()
        main.removeCallbacks(endTask)
        main.removeCallbacks(prepareTask)
        withApple(idx) { track ->
            if (!isOn) return@withApple
            if (track == null) {
                log("[${idx + 1}] \"${item.title}\" / ${item.artist}: not in Apple Music, skipping")
                pos = idx
                return@withApple if (forward) advance() else if (idx > 0) playIdx(idx - 1, false) else Unit
            }
            pos = idx
            save()
            request(idx, if (pendingIdx == idx && pendingVia == Via.PREPARE) Via.PREPARE else Via.MEDIA_ID)
        }
    }

    /** Calls back on the main thread with the Apple song (null: Apple Music doesn't have it). */
    private fun withApple(idx: Int, done: (AppleCatalog.Track?) -> Unit) {
        val item = items[idx]
        if (item.apple != null || item.missing) return done(item.apple)
        item.waiting?.let { it += done; return }
        item.waiting = mutableListOf(done)
        val t0 = SystemClock.elapsedRealtime()
        worker.execute {
            val (found, error) = try {
                AppleCatalog.find(ctx, item.title, item.artist) to null
            } catch (e: IOException) {
                null to e.message
            } catch (e: JSONException) {
                null to "bad reply"
            }
            main.post {
                if (found != null) {
                    item.apple = found
                    log("[${idx + 1}] match: \"${item.title}\" / ${item.artist} → \"${found.title}\" / ${found.artist} " +
                        "(${formatTime(found.durationMs)}, id ${found.id}) [${SystemClock.elapsedRealtime() - t0} ms]")
                } else if (error == null) {
                    item.missing = true
                } else {
                    log("[${idx + 1}] lookup failed ($error)")
                }
                save()
                val callers = item.waiting.orEmpty()
                item.waiting = null
                callers.forEach { it(found) }
            }
        }
    }

    private fun request(idx: Int, via: Via) {
        val c = am ?: return stop("Apple Music session disappeared")
        val t = items[idx].apple ?: return
        pendingIdx = idx
        requestedAt = SystemClock.elapsedRealtime()
        when (via) {
            Via.PREPARE -> {
                // Already loaded by prepare: just start it.
                pendingVia = Via.MEDIA_ID
                c.transportControls.playFromMediaId(t.id.toString(), null)
                log("[${idx + 1}] asked: play (prepared ahead)")
            }
            Via.MEDIA_ID -> {
                pendingVia = Via.MEDIA_ID
                c.transportControls.playFromMediaId(t.id.toString(), null)
                log("[${idx + 1}] asked: play id ${t.id}")
            }
            Via.SEARCH -> {
                pendingVia = Via.SEARCH
                val song = Diagnostics.Song(t.title, t.artist, durationMs = t.durationMs)
                c.transportControls.playFromSearch("${t.title} ${t.artist}", Diagnostics.searchExtras(song))
                log("[${idx + 1}] asked: search \"${t.title} ${t.artist}\"")
            }
        }
        main.removeCallbacks(startTimeout)
        main.postDelayed(startTimeout, ATTEMPT_MS)
    }

    private val startTimeout = Runnable {
        if (!isOn || pendingIdx < 0) return@Runnable
        val idx = pendingIdx
        val s = am?.playbackState
        log("[${idx + 1}] Apple Music now: ${Diagnostics.stateName(s?.state)}, \"${am?.let { Diagnostics.Song.from(it).title }}\"" +
            (s?.errorMessage?.let { ", error: $it" } ?: ""))
        if (pendingVia == Via.MEDIA_ID) {
            log("[${idx + 1}] didn't start within ${ATTEMPT_MS / 1000} s: trying search")
            request(idx, Via.SEARCH)
        } else {
            log("[${idx + 1}] Apple Music didn't play it: skipping")
            pendingIdx = -1
            advance()
        }
    }

    /** Asks Apple Music to load the next song while this one plays. Off if it cuts this song short. */
    private val prepareTask = Runnable {
        if (!isOn || !prepareWorks || pendingIdx >= 0) return@Runnable
        val next = pos + 1
        if (next >= items.size) return@Runnable
        withApple(next) { t ->
            if (!isOn || t == null || pendingIdx >= 0) return@withApple
            pendingIdx = next
            pendingVia = Via.PREPARE
            am?.transportControls?.prepareFromMediaId(t.id.toString(), null)
            log("[${next + 1}] asked: prepare id ${t.id} (current song keeps playing?)")
        }
    }

    private val endTask = Runnable {
        if (!isOn) return@Runnable
        endingAt = SystemClock.elapsedRealtime()
        log("[${pos + 1}] ending: next")
        advance()
    }

    private val appleCallback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = onApple()
        override fun onMetadataChanged(metadata: MediaMetadata?) = onApple()
        override fun onSessionDestroyed() {
            am = null
            if (isOn) stop("Apple Music closed")
        }
    }

    private fun onApple() {
        if (!isOn || refilling) return // Apple Music pauses while YouTube Music briefly plays
        val c = am ?: return
        val got = Diagnostics.Song.from(c)
        val state = c.playbackState?.state
        val pending = items.getOrNull(pendingIdx)?.apple
        if (pending != null && matches(pending, got)) {
            when {
                pendingVia == Via.PREPARE && state == PlaybackState.STATE_PLAYING -> {
                    log("[${pendingIdx + 1}] prepare started it at once (cut the song before short): prepare-ahead off")
                    prepareWorks = false
                    started(pendingIdx)
                }
                pendingVia == Via.PREPARE -> {
                    // Loaded but the current song stopped: prepare can't run alongside. Start it now.
                    log("[${pendingIdx + 1}] prepare stopped the current song (${Diagnostics.stateName(state)}): prepare-ahead off")
                    prepareWorks = false
                    val idx = pendingIdx
                    pos = idx
                    request(idx, Via.MEDIA_ID)
                }
                state == PlaybackState.STATE_PLAYING -> started(pendingIdx)
                state == PlaybackState.STATE_PAUSED -> c.transportControls.play()
            }
            return
        }
        val current = items.getOrNull(pos)?.apple ?: return
        if (pendingIdx >= 0 && pendingVia != Via.PREPARE) return // waiting for ours; ignore the old song
        if (got.title.isNotBlank() && !matches(current, got)) {
            val nearEnd = SystemClock.elapsedRealtime() > endsAt - PREPARE_AHEAD_MS
            if (nearEnd) {
                log("[${pos + 1}] Apple Music's own autoplay started \"${got.title}\": next pick")
                return advance()
            }
            return stop("you picked \"${got.title}\" in Apple Music")
        }
        if (state == PlaybackState.STATE_STOPPED) {
            log("[${pos + 1}] Apple Music stopped: next")
            return advance()
        }
        scheduleTimers()
    }

    private fun started(idx: Int) {
        main.removeCallbacks(startTimeout)
        val now = SystemClock.elapsedRealtime()
        val gap = if (endingAt > 0 && now - endingAt < 60_000) ", ${String.format(Locale.ROOT, "%.1f", (now - endingAt) / 1000.0)} s after the last song's handoff" else ""
        log("[${idx + 1}] PLAYING after ${String.format(Locale.ROOT, "%.1f", (now - requestedAt) / 1000.0)} s$gap")
        endingAt = 0
        pendingIdx = -1
        pos = idx
        save()
        val dur = items[idx].apple?.durationMs ?: 0
        if (fast && dur > FAST_TAIL_MS * 2) {
            am?.transportControls?.seekTo(dur - FAST_TAIL_MS)
            log("[${idx + 1}] fast test: jumped to ${formatTime(dur - FAST_TAIL_MS)}")
        }
        scheduleTimers()
        // Look up the next song now, so the handoff needs no network.
        if (pos + 1 < items.size) withApple(pos + 1) {}
    }

    /** One timer for the handoff (and one for prepare), from Apple Music's reported position. */
    private fun scheduleTimers() {
        main.removeCallbacks(endTask)
        main.removeCallbacks(prepareTask)
        val c = am ?: return
        val s = c.playbackState ?: return
        if (s.state != PlaybackState.STATE_PLAYING) return // paused by the owner: wait
        val dur = c.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION)?.takeIf { it > 0 }
            ?: items.getOrNull(pos)?.apple?.durationMs ?: return
        val speed = s.playbackSpeed.takeIf { it > 0f } ?: 1f
        val left = ((dur - positionNow(s)) / speed).toLong()
        endsAt = SystemClock.elapsedRealtime() + left
        main.postDelayed(endTask, (left - HANDOFF_EARLY_MS).coerceAtLeast(0))
        if (prepareWorks && left > PREPARE_AHEAD_MS) main.postDelayed(prepareTask, left - PREPARE_AHEAD_MS)
    }

    /**
     * Apple Music offers an "Autoplay" button to other apps. Turning it off would stop its own
     * autoplay racing our next pick, but pressing it blind could turn it on, so first we log
     * what it reports (its extras and icon may say whether it's on).
     */
    private fun logAutoplay(c: MediaController) {
        val a = c.playbackState?.customActions?.firstOrNull { it.name?.toString().equals("Autoplay", true) }
            ?: return log("Apple Music: no Autoplay button")
        val extras = a.extras?.let { b -> b.keySet().joinToString { k -> "$k=${b.get(k)}" } } ?: "none"
        log("Apple Music Autoplay button: action=${a.action}, icon=${a.icon}, extras: $extras")
    }

    private fun positionNow(s: PlaybackState): Long {
        if (s.state != PlaybackState.STATE_PLAYING) return s.position
        return s.position + ((SystemClock.elapsedRealtime() - s.lastPositionUpdateTime) * s.playbackSpeed).toLong()
    }

    private fun matches(t: AppleCatalog.Track, got: Diagnostics.Song) =
        got.title.isNotBlank() &&
            !Diagnostics.verdict(Diagnostics.Song(t.title, t.artist, durationMs = t.durationMs), got).startsWith("MISMATCH")

    private fun session(pkg: String) = Diagnostics.sessions(ctx)?.firstOrNull { it.packageName == pkg }

    private fun log(line: String) {
        Log.i(TAG, "relay: $line")
        ui?.invoke(line)
    }

    // ------------------------------------------------------------------ saved list

    /** Keeps the list small: drops the oldest played songs. */
    private fun trim() {
        val extra = items.size - MAX_ITEMS
        if (extra <= 0 || pos < extra) return
        repeat(extra) { items.removeAt(0) }
        pos -= extra
        if (pendingIdx >= 0) pendingIdx -= extra
    }

    private fun load() {
        if (items.isNotEmpty()) return
        val f = File(ctx.filesDir, FILE)
        if (!f.exists()) return
        try {
            val o = JSONObject(f.readText())
            pos = o.optInt("pos", -1)
            ytLastId = o.optLong("ytLastId", -1L)
            prepareWorks = o.optBoolean("prepareWorks", true)
            val a = o.optJSONArray("items") ?: JSONArray()
            for (i in 0 until a.length()) {
                val j = a.getJSONObject(i)
                items += Item(j.optString("t"), j.optString("a")).apply {
                    missing = j.optBoolean("missing")
                    if (j.has("id")) apple = AppleCatalog.Track(j.getLong("id"), j.optString("at"), j.optString("aa"), j.optLong("d"))
                }
            }
            if (pos >= items.size) pos = items.size - 1
        } catch (e: JSONException) {
            items.clear()
            pos = -1
        } catch (e: IOException) {
            items.clear()
            pos = -1
        }
    }

    /** Small file (≤ [MAX_ITEMS] songs), written after each change. */
    private fun save() {
        val a = JSONArray()
        items.forEach { item ->
            a.put(JSONObject().apply {
                put("t", item.title)
                put("a", item.artist)
                if (item.missing) put("missing", true)
                item.apple?.let { put("id", it.id); put("at", it.title); put("aa", it.artist); put("d", it.durationMs) }
            })
        }
        val o = JSONObject()
            .put("pos", pos)
            .put("ytLastId", ytLastId)
            .put("prepareWorks", prepareWorks)
            .put("items", a)
        try {
            File(ctx.filesDir, FILE).writeText(o.toString())
        } catch (e: IOException) {
            Log.w(TAG, "relay: couldn't save the list: ${e.message}")
        }
    }
}
