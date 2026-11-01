package com.arktheogen.musicwidget

import android.os.Handler
import android.os.Looper
import android.util.LruCache
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * Lyrics from LRCLIB (free, no account). None of the music apps share lyrics,
 * so we look the song up by title + artist and pick the closest duration match.
 */
object Lyrics {
    data class Line(val timeMs: Long, val text: String)

    sealed interface Result {
        data class Synced(val lines: List<Line>) : Result
        data class Plain(val text: String) : Result
        data object Instrumental : Result
        data object NotFound : Result
        data object Failed : Result
    }

    private const val SEARCH_URL = "https://lrclib.net/api/search"
    private const val USER_AGENT = "MusicWidget/0.1 (https://github.com/ARKTHEOGEN/Music-widget-android)"
    private const val DURATION_TOLERANCE_S = 3.0

    private val cache = LruCache<String, Result>(32)
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    /** Calls [onResult] on the main thread. Results (except failures) are cached per track. */
    fun load(key: String, title: String, artist: String, durationMs: Long, onResult: (Result) -> Unit) {
        cache.get(key)?.let { onResult(it); return }
        worker.execute {
            val result = try {
                fetch(cleanTitle(title), cleanArtist(artist), durationMs / 1000.0)
            } catch (e: IOException) {
                Result.Failed
            } catch (e: JSONException) {
                Result.Failed
            }
            if (result != Result.Failed) cache.put(key, result)
            main.post { onResult(result) }
        }
    }

    private fun fetch(title: String, artist: String, durationS: Double): Result {
        val candidates = search("track_name=${enc(title)}&artist_name=${enc(artist)}")
            .ifEmpty { search("q=${enc("$title $artist")}") }
        val close = candidates.filter {
            durationS <= 0 || abs(it.optDouble("duration", 0.0) - durationS) <= DURATION_TOLERANCE_S
        }.ifEmpty { candidates }
        val best = close.firstOrNull { it.str("syncedLyrics") != null }
            ?: close.firstOrNull { it.str("plainLyrics") != null }
            ?: close.firstOrNull { it.optBoolean("instrumental") }
            ?: return Result.NotFound

        if (best.optBoolean("instrumental")) return Result.Instrumental
        best.str("syncedLyrics")?.let { lrc ->
            val lines = parseLrc(lrc)
            if (lines.isNotEmpty()) return Result.Synced(lines)
        }
        return best.str("plainLyrics")?.let { Result.Plain(it) } ?: Result.NotFound
    }

    private fun search(query: String): List<JSONObject> {
        val conn = URL("$SEARCH_URL?$query").openConnection() as HttpURLConnection
        conn.connectTimeout = 8000
        conn.readTimeout = 8000
        conn.setRequestProperty("User-Agent", USER_AGENT)
        try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) throw IOException("HTTP ${conn.responseCode}")
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val arr = JSONArray(body)
            return List(arr.length()) { arr.getJSONObject(it) }
        } finally {
            conn.disconnect()
        }
    }

    // ---------------------------------------------------------------- parsing

    private val TIME_TAG = Regex("""\[(\d{1,3}):(\d{1,2}(?:[.:]\d{1,3})?)]""")

    /** "[01:23.45] text" → Line(83450, "text"). Lines with several tags repeat. */
    fun parseLrc(lrc: String): List<Line> {
        val out = ArrayList<Line>()
        for (raw in lrc.lineSequence()) {
            val tags = TIME_TAG.findAll(raw).toList()
            if (tags.isEmpty()) continue
            val text = raw.substring(tags.last().range.last + 1).trim()
            for (tag in tags) {
                val min = tag.groupValues[1].toLong()
                val sec = tag.groupValues[2].replace(':', '.').toDouble()
                out += Line(min * 60_000 + (sec * 1000).toLong(), text)
            }
        }
        out.sortBy { it.timeMs }
        return out
    }

    // ---------------------------------------------------------------- clean-up

    private val NOISE = Regex(
        """\s*[(\[][^)\]]*\b(official|video|lyrics?|audio|visuali[sz]er|remaster(ed)?|explicit|hd|4k|mv)\b[^)\]]*[)\]]""",
        RegexOption.IGNORE_CASE,
    )
    private val FEAT = Regex("""\s*[(\[]?\s*\b(feat\.?|ft\.|featuring)\s.*$""", RegexOption.IGNORE_CASE)
    private val DASH_SUFFIX = Regex("""\s+-\s+.*\b(remaster(ed)?|version|edit|live|mono|stereo)\b.*$""", RegexOption.IGNORE_CASE)

    /** "Song (Official Video) [feat. X]" → "Song". Falls back to the original if nothing is left. */
    fun cleanTitle(t: String): String =
        t.replace(NOISE, "").replace(FEAT, "").replace(DASH_SUFFIX, "").trim().ifEmpty { t }

    /** "A, B & C" / "A - Topic" → "A". */
    fun cleanArtist(a: String): String =
        a.removeSuffix(" - Topic").split(",", " & ", " feat", " ft.").first().trim().ifEmpty { a }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    /** org.json returns the text "null" for JSON nulls; treat those and blanks as missing. */
    private fun JSONObject.str(name: String): String? =
        if (isNull(name)) null else optString(name).takeIf { it.isNotBlank() }
}
