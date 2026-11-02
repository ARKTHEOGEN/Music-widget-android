package com.arktheogen.musicwidget

import android.app.Activity
import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.support.v4.media.session.PlaybackStateCompat
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * Test build: measures what each music app really supports on this phone,
 * so the handoff feature is built on facts instead of guesses.
 */
object Diagnostics {

    enum class Method { SEARCH, LINK }

    private val ACTION_NAMES = listOf(
        PlaybackStateCompat.ACTION_PLAY to "play",
        PlaybackStateCompat.ACTION_PAUSE to "pause",
        PlaybackStateCompat.ACTION_PLAY_PAUSE to "play_pause",
        PlaybackStateCompat.ACTION_STOP to "stop",
        PlaybackStateCompat.ACTION_SKIP_TO_NEXT to "next",
        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS to "previous",
        PlaybackStateCompat.ACTION_SEEK_TO to "seek",
        PlaybackStateCompat.ACTION_FAST_FORWARD to "fast_forward",
        PlaybackStateCompat.ACTION_REWIND to "rewind",
        PlaybackStateCompat.ACTION_SET_RATING to "rating",
        PlaybackStateCompat.ACTION_SKIP_TO_QUEUE_ITEM to "jump_to_queue_item",
        PlaybackStateCompat.ACTION_PLAY_FROM_SEARCH to "play_from_search",
        PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID to "play_from_media_id",
        PlaybackStateCompat.ACTION_PLAY_FROM_URI to "play_from_uri",
        PlaybackStateCompat.ACTION_PREPARE to "prepare",
        PlaybackStateCompat.ACTION_PREPARE_FROM_SEARCH to "prepare_from_search",
        PlaybackStateCompat.ACTION_PREPARE_FROM_MEDIA_ID to "prepare_from_media_id",
        PlaybackStateCompat.ACTION_PREPARE_FROM_URI to "prepare_from_uri",
        PlaybackStateCompat.ACTION_SET_SHUFFLE_MODE to "shuffle",
        PlaybackStateCompat.ACTION_SET_REPEAT_MODE to "repeat",
        PlaybackStateCompat.ACTION_SET_PLAYBACK_SPEED to "speed",
    )

    /** One song link per app, used only to ask Android "would this app open it?". */
    private val SAMPLE_LINKS = mapOf(
        "com.apple.android.music" to "https://music.apple.com/us/album/a/1440818839?i=1440818843",
        "com.spotify.music" to "https://open.spotify.com/track/4cOdK2wGLETKBW3PvgPWqT",
        "com.google.android.apps.youtube.music" to "https://music.youtube.com/watch?v=dQw4w9WgXcQ",
    )
    private val ODESLI_PLATFORM = mapOf(
        "com.spotify.music" to "spotify",
        "com.google.android.apps.youtube.music" to "youtubeMusic",
    )
    private const val APPLE_PKG = "com.apple.android.music"
    private const val WATCH_TIMEOUT_MS = 15_000L

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var running = false

    fun country(): String = Locale.getDefault().country.ifBlank { "US" }

    internal fun sessions(ctx: Context): List<MediaController>? = try {
        ctx.getSystemService(MediaSessionManager::class.java)
            .getActiveSessions(SessionHub.listenerComponent(ctx))
    } catch (e: SecurityException) {
        null
    }

    // ================================================================ report

    fun report(ctx: Context): String = buildString {
        appendLine("Music Widget diagnostics v${versionOf(ctx, ctx.packageName)}")
        appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        appendLine("Country: ${country()}")
        appendLine("Notification access: ${if (SessionHub.hasAccess(ctx)) "granted" else "MISSING"}")
        appendLine()
        appendLine("== Apps ==")
        for (app in MusicApps.ALL) {
            val version = versionOf(ctx, app.pkg)
            if (version == null) {
                appendLine("${app.label}: not installed")
                continue
            }
            appendLine("${app.label} v$version")
            appendLine("  play-from-search intent: ${yesNo(resolves(ctx, searchIntent(app.pkg, Song("test", "test"))))}")
            val link = SAMPLE_LINKS[app.pkg]
            if (link != null) {
                val view = Intent(Intent.ACTION_VIEW, Uri.parse(link)).addCategory(Intent.CATEGORY_BROWSABLE).setPackage(app.pkg)
                appendLine("  opens its song links: ${yesNo(resolves(ctx, view))}")
            }
        }
        appendLine()
        appendLine("== Active media sessions ==")
        val list = sessions(ctx)
        when {
            list == null -> appendLine("(no access)")
            list.isEmpty() -> appendLine("(none: play a song first)")
            else -> list.forEach { appendSession(it) }
        }
    }

    private fun StringBuilder.appendSession(c: MediaController) {
        val s = c.playbackState
        val m = c.metadata
        val duration = m?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        appendLine("[${c.packageName}] state=${stateName(s?.state)} at ${formatTime(s?.position ?: 0L)} / ${formatTime(duration)}")

        val actions = s?.actions ?: 0L
        val supported = ACTION_NAMES.filter { (bit, _) -> (actions and bit) != 0L }.joinToString { it.second }
        appendLine("  commands: ${supported.ifEmpty { "(none)" }}")
        val custom = s?.customActions.orEmpty().joinToString { "${it.name} [${it.action}]" }
        appendLine("  app buttons: ${custom.ifEmpty { "(none)" }}")

        val queue = c.queue
        if (queue.isNullOrEmpty()) {
            appendLine("  up-next list: NOT SHARED")
        } else {
            appendLine("  up-next list: SHARED, ${queue.size} items, title=\"${c.queueTitle ?: ""}\"")
            val idx = queue.indexOfFirst { it.queueId == s?.activeQueueItemId }
            if (idx < 0) appendLine("    (current song not marked in the list)")
            queue.drop(idx + 1).take(5).forEachIndexed { i, q ->
                appendLine("    ${i + 1}. ${q.description.title} / ${q.description.subtitle}")
            }
        }

        appendLine("  details shared:")
        val keys = m?.keySet()?.sorted().orEmpty()
        if (keys.isEmpty()) appendLine("    (none)")
        keys.forEach { key -> appendLine("    ${key.removePrefix("android.media.metadata.")} = ${valueOf(m!!, key)}") }
        val extras = c.extras?.keySet()?.sorted()?.joinToString().orEmpty()
        appendLine("  extras: ${extras.ifEmpty { "(none)" }}")
        appendLine("  rating type: ${c.ratingType}, flags: ${c.flags}")
        appendLine()
    }

    /** Metadata values have no type tag, so try each kind in turn. */
    private fun valueOf(m: MediaMetadata, key: String): String {
        m.getText(key)?.let { return it.toString().take(80) }
        m.getBitmap(key)?.let { return "image ${it.width}x${it.height}" }
        m.getRating(key)?.let { return "rating $it" }
        return m.getLong(key).toString()
    }

    internal fun stateName(state: Int?) = when (state) {
        PlaybackState.STATE_PLAYING -> "PLAYING"
        PlaybackState.STATE_PAUSED -> "PAUSED"
        PlaybackState.STATE_STOPPED -> "STOPPED"
        PlaybackState.STATE_BUFFERING -> "BUFFERING"
        PlaybackState.STATE_CONNECTING -> "CONNECTING"
        PlaybackState.STATE_ERROR -> "ERROR"
        PlaybackState.STATE_NONE -> "NONE"
        null -> "unknown"
        else -> "state $state"
    }

    private fun versionOf(ctx: Context, pkg: String): String? = try {
        ctx.packageManager.getPackageInfo(pkg, 0).versionName ?: "?"
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }

    @Suppress("DEPRECATION")
    private fun resolves(ctx: Context, intent: Intent) = ctx.packageManager.queryIntentActivities(intent, 0).isNotEmpty()

    private fun yesNo(b: Boolean) = if (b) "YES" else "no"

    // ================================================================ handoff test

    internal data class Song(
        val title: String,
        val artist: String,
        val album: String = "",
        val durationMs: Long = 0,
        val positionMs: Long = 0,
    ) {
        companion object {
            fun from(c: MediaController): Song {
                val m = c.metadata
                val s = c.playbackState
                val pos = when {
                    s == null -> 0L
                    s.state == PlaybackState.STATE_PLAYING ->
                        s.position + ((SystemClock.elapsedRealtime() - s.lastPositionUpdateTime) * s.playbackSpeed).toLong()
                    else -> s.position
                }
                return Song(
                    title = m?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty(),
                    artist = m?.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty(),
                    album = m?.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty(),
                    durationMs = m?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L,
                    positionMs = pos,
                )
            }
        }
    }

    /**
     * Pauses the playing app, asks [target] to play the same song by [method],
     * waits up to 15 s, then reports what started, whether it matched, and whether seek worked.
     * [log] is always called on the main thread.
     */
    fun runHandoff(activity: Activity, target: MusicApp, method: Method, log: (String) -> Unit) {
        if (running) {
            log("A test is already running; wait for \"done\".")
            return
        }
        val all = sessions(activity) ?: run { log("No notification access."); return }
        val src = all.firstOrNull { it.packageName != target.pkg && it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: all.firstOrNull { it.packageName != target.pkg && MusicApps.isSupported(it.packageName) }
            ?: run { log("Play a song in a different app first, then test ${target.label}."); return }
        val song = Song.from(src)
        if (song.title.isBlank()) {
            log("The playing app shared no song title.")
            return
        }
        running = true
        val srcLabel = MusicApps.byPkg(src.packageName)?.label ?: src.packageName
        log("")
        log("> ${method.name} handoff: $srcLabel -> ${target.label}")
        log("  source: \"${song.title}\" / ${song.artist} (${formatTime(song.durationMs)}) at ${formatTime(song.positionMs)}")
        src.transportControls.pause()
        val startedAt = SystemClock.elapsedRealtime()
        val appCtx = activity.applicationContext

        when (method) {
            Method.SEARCH -> {
                val how = startSearch(activity, target, song)
                log("  method: $how")
                if (how.startsWith("FAILED")) running = false
                else watch(appCtx, target, song, startedAt, log)
            }
            Method.LINK -> worker.execute {
                var error: String? = null
                val url = try {
                    findLink(target, song)
                } catch (e: IOException) {
                    error = e.message
                    null
                } catch (e: JSONException) {
                    error = "bad reply"
                    null
                }
                main.post {
                    if (url == null) {
                        log("  FAILED: no matching link found${error?.let { " ($it)" } ?: ""}")
                        running = false
                        return@post
                    }
                    log("  link: $url")
                    val view = Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage(target.pkg)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    try {
                        activity.startActivity(view)
                        watch(appCtx, target, song, startedAt, log)
                    } catch (e: ActivityNotFoundException) {
                        log("  FAILED: ${target.label} can't open this link")
                        running = false
                    }
                }
            }
        }
    }

    internal fun searchExtras(song: Song) = Bundle().apply {
        putString(MediaStore.EXTRA_MEDIA_FOCUS, MediaStore.Audio.Media.ENTRY_CONTENT_TYPE)
        putString(MediaStore.EXTRA_MEDIA_TITLE, song.title)
        putString(MediaStore.EXTRA_MEDIA_ARTIST, song.artist)
        if (song.album.isNotBlank()) putString(MediaStore.EXTRA_MEDIA_ALBUM, song.album)
    }

    internal fun searchIntent(pkg: String, song: Song) =
        Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
            .setPackage(pkg)
            .putExtra(SearchManager.QUERY, "${song.title} ${song.artist}".trim())
            .putExtras(searchExtras(song))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Prefer the running app's own session; otherwise send the system intent (works when closed). */
    private fun startSearch(activity: Activity, target: MusicApp, song: Song): String {
        val live = sessions(activity)?.firstOrNull { it.packageName == target.pkg }
        val acceptsSearch = ((live?.playbackState?.actions ?: 0L) and PlaybackState.ACTION_PLAY_FROM_SEARCH) != 0L
        if (live != null && acceptsSearch) {
            live.transportControls.playFromSearch("${song.title} ${song.artist}".trim(), searchExtras(song))
            return "session play-from-search (app was running)"
        }
        return try {
            activity.startActivity(searchIntent(target.pkg, song))
            "intent play-from-search " + if (live == null) "(app was closed)" else "(app running; its session refuses search)"
        } catch (e: ActivityNotFoundException) {
            "FAILED: ${target.label} doesn't accept play-from-search"
        }
    }

    /** Background thread: iTunes Search for the Apple link, then Song.link for the others. */
    private fun findLink(target: MusicApp, song: Song): String? {
        val cc = country()
        val term = Http.enc("${Lyrics.cleanTitle(song.title)} ${Lyrics.cleanArtist(song.artist)}")
        val json = JSONObject(Http.get("https://itunes.apple.com/search?term=$term&entity=song&limit=10&country=$cc"))
        val results = json.optJSONArray("results") ?: return null
        val items = List(results.length()) { results.getJSONObject(it) }
        val wanted = norm(Lyrics.cleanTitle(song.title))
        val best = items.filter { norm(Lyrics.cleanTitle(it.optString("trackName"))).let { t -> t.contains(wanted) || wanted.contains(t) } }
            .ifEmpty { items }
            .minByOrNull { if (song.durationMs > 0) abs(it.optLong("trackTimeMillis") - song.durationMs) else 0L }
            ?: return null
        val appleUrl = best.optString("trackViewUrl").takeIf { it.isNotBlank() } ?: return null
        if (target.pkg == APPLE_PKG) return appleUrl

        val platform = ODESLI_PLATFORM[target.pkg] ?: return null
        val links = JSONObject(Http.get("https://api.song.link/v1-alpha.1/links?url=${Http.enc(appleUrl)}&userCountry=$cc"))
        return links.optJSONObject("linksByPlatform")?.optJSONObject(platform)?.optString("url")?.takeIf { it.isNotBlank() }
    }

    /** Polls twice a second until [target] is playing, then evaluates. */
    private fun watch(ctx: Context, target: MusicApp, song: Song, startedAt: Long, log: (String) -> Unit) {
        val poll = object : Runnable {
            override fun run() {
                val playing = sessions(ctx)?.firstOrNull {
                    it.packageName == target.pkg && it.playbackState?.state == PlaybackState.STATE_PLAYING
                }
                if (playing == null) {
                    if (SystemClock.elapsedRealtime() - startedAt < WATCH_TIMEOUT_MS) main.postDelayed(this, 500)
                    else {
                        log("  RESULT: ${target.label} did not start playing within 15 s")
                        log("  done")
                        running = false
                    }
                    return
                }
                val secs = (SystemClock.elapsedRealtime() - startedAt) / 1000.0
                // Give the app a moment to publish the new song's details.
                main.postDelayed({ evaluate(ctx, target, song, secs, log) }, 1500)
            }
        }
        main.postDelayed(poll, 500)
    }

    private fun evaluate(ctx: Context, target: MusicApp, song: Song, secs: Double, log: (String) -> Unit) {
        val c = sessions(ctx)?.firstOrNull { it.packageName == target.pkg }
        if (c == null) {
            log("  RESULT: ${target.label} stopped reporting")
            log("  done")
            running = false
            return
        }
        val got = Song.from(c)
        log("  started after ${String.format(Locale.ROOT, "%.1f", secs)} s: \"${got.title}\" / ${got.artist} (${formatTime(got.durationMs)})")
        log("  match: ${verdict(song, got)}")

        val canSeek = ((c.playbackState?.actions ?: 0L) and PlaybackState.ACTION_SEEK_TO) != 0L
        if (!canSeek || song.positionMs < 5000) {
            log("  seek: ${if (canSeek) "skipped (source was near the start)" else "NOT SUPPORTED"}")
            log("  done")
            running = false
            return
        }
        c.transportControls.seekTo(song.positionMs)
        main.postDelayed({
            val now = sessions(ctx)?.firstOrNull { it.packageName == target.pkg }?.let { Song.from(it).positionMs }
            val ok = now != null && abs(now - song.positionMs) < 4000
            log("  seek to ${formatTime(song.positionMs)}: ${if (ok) "OK" else "FAILED"} (now at ${now?.let { formatTime(it) } ?: "?"})")
            log("  done")
            running = false
        }, 2000)
    }

    private fun norm(s: String) = s.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }

    internal fun verdict(a: Song, b: Song): String {
        val t1 = norm(Lyrics.cleanTitle(a.title))
        val t2 = norm(Lyrics.cleanTitle(b.title))
        val titleOk = t1.isNotEmpty() && t2.isNotEmpty() && (t1.contains(t2) || t2.contains(t1))
        val a1 = norm(Lyrics.cleanArtist(a.artist))
        val a2 = norm(Lyrics.cleanArtist(b.artist))
        val artistOk = a1.isNotEmpty() && a2.isNotEmpty() && (a1.contains(a2) || a2.contains(a1))
        val diffS = abs(a.durationMs - b.durationMs) / 1000
        val lengthOk = a.durationMs <= 0 || b.durationMs <= 0 || diffS <= 3
        return when {
            titleOk && artistOk && lengthOk -> "EXACT"
            titleOk && artistOk -> "CLOSE (length differs by $diffS s)"
            titleOk -> "CLOSE (artist differs)"
            else -> "MISMATCH"
        }
    }
}
